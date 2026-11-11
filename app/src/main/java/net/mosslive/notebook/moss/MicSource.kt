package net.mosslive.notebook.moss

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 上行麦克风：16 kHz 单声道 PCM16，20 ms 一帧 = 320 样本 = 640 字节。
 * 用 VOICE_COMMUNICATION 源，尽量吃到系统硬件 AEC（手机外放时不被自己的声音打断）。
 *
 * 诊断：
 *  - dumpPath 不为空时，把「实际发出去的」PCM 原样落盘（16k mono s16le），
 *    可以拉回来量电平、听、或本地 ASR 复算 —— 判断问题出在采集端还是服务端。
 *  - 每 500 ms 打一条上行电平（dBFS），用来判断是不是电平太低导致识别崩。
 */
class MicSource(
    private val gain: Float = 1.0f,
    private val enableNs: Boolean = false,
    private val dumpPath: String? = null,
    private val onLevel: (Float) -> Unit = {},
    /** MOSS 是否正在说话（由 MossClient 按下行事件维护）。说话期间用更严的门。 */
    private val speakingGate: () -> Boolean = { false },
    private val onFrame: (ByteArray) -> Unit,
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
        private const val TAG = "MicSource"
        private const val METER_SAMPLES = 8000      // 500 ms

        // ---- 噪声门（打断阈值）----
        // 服务端 VAD 对上行里任何「像语音」的能量都会 barge_in，环境音（电视、聊天、
        // 键盘）太响就把 MOSS 打断。服务端 session.update 没有暴露阈值参数（实测只有
        // persona/instructions/tools），所以在客户端把不达标的上行静音：
        // 门关着 → 发全零帧（保持 20ms 流节奏），真说话超过 attack 帧数才开门。
        private const val GATE_HYSTERESIS_DB = 3f          // 迟滞：关门比开门低 3dB
        private const val GATE_ATTACK_FRAMES_IDLE = 5      // 100ms 连续超阈才开门（平时）
        private const val GATE_ATTACK_FRAMES_SPEAK = 10    // 200ms（MOSS 说话时更严）
        private const val GATE_RELEASE_FRAMES_IDLE = 15    // 300ms 低于阈值关门
        private const val GATE_RELEASE_FRAMES_SPEAK = 25   // 500ms
        private const val GATE_IDLE_DB = -45f              // 平时开门底线（dBFS）
        private const val GATE_IDLE_MARGIN_DB = 10f        // 底线 + 噪底余量
        private const val GATE_SPEAK_DB = -38f             // MOSS 说话时开门底线（更严）
        private const val GATE_SPEAK_MARGIN_DB = 14f
        private const val NOISE_FLOOR_MIN_DB = -60f
        private const val NOISE_FLOOR_MAX_DB = -30f
    }

    // 自适应噪底：快速向下跟踪（真安静时降得下来），缓慢向上漂（噪声变大能跟上）
    private var noiseDb = NOISE_FLOOR_MIN_DB
    private var gateOpen = false
    private var gateAbove = 0
    private var gateBelow = 0
    @Volatile private var lastGateDb = 0f

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var dump: BufferedOutputStream? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    var aecState: String = "未初始化"
        private set
    var dumpFile: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): String? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return "getMinBufferSize 失败($minBuf)"

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, FRAME_SAMPLES * 2 * 4)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 创建失败", e)
            return "AudioRecord 创建失败: ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "AudioRecord 初始化失败 state=${rec.state}"
        }
        record = rec

        val parts = mutableListOf<String>()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.audioSessionId)
                aec?.enabled = true
                parts += if (aec?.enabled == true) "AEC 已开" else "AEC 开失败"
            } else {
                parts += "AEC 不可用"
            }
        } catch (e: Exception) {
            parts += "AEC 异常(${e.javaClass.simpleName})"
        }
        try {
            if (enableNs && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.audioSessionId)
                ns?.enabled = true
                if (ns?.enabled == true) parts += "NS 已开"
            } else {
                parts += "NS 关"
            }
        } catch (_: Exception) {
            parts += "NS 异常"
        }
        parts += "增益 x$gain"
        parts += "噪声门 开（MOSS说话时阈值更高）"
        aecState = parts.joinToString(" / ")

        dumpPath?.let { p ->
            try {
                val f = File(p)
                f.parentFile?.mkdirs()
                dump = BufferedOutputStream(FileOutputStream(f))
                dumpFile = f.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "上行落盘失败: ${e.message}")
            }
        }

        Log.i(TAG, "上行已开: $aecState，sessionId=${rec.audioSessionId}，dump=${dumpFile ?: "关"}")

        rec.startRecording()
        running = true
        worker = thread(name = "mic-uplink") {
            val buf = ShortArray(FRAME_SAMPLES)
            val bytes = ByteArray(FRAME_SAMPLES * 2)
            var meterSum = 0.0
            var meterN = 0
            var meterPeak = 0
            while (running) {
                val n = rec.read(buf, 0, FRAME_SAMPLES)
                if (n <= 0) continue
                var j = 0
                var i = 0
                var frameSum = 0.0
                while (i < n) {
                    val raw = buf[i].toInt()
                    val s = if (gain == 1.0f) raw else (raw * gain).toInt().coerceIn(-32768, 32767)
                    bytes[j] = (s and 0xFF).toByte()
                    bytes[j + 1] = ((s shr 8) and 0xFF).toByte()
                    frameSum += s.toDouble() * s
                    meterSum += raw.toDouble() * raw
                    if (abs(raw) > meterPeak) meterPeak = abs(raw)
                    meterN++
                    i++
                    j += 2
                }
                val frameRms = sqrt(frameSum / n)
                val frameDb = if (frameRms < 1.0) -99.0 else 20 * log10(frameRms / 32768.0)
                val gate = gateFrame(frameDb.toFloat())
                val frame = if (n == FRAME_SAMPLES) bytes else bytes.copyOf(j)
                if (gate != null && !gate) {
                    // 门关着：上行静音（保持 20ms 节奏），环境音不进服务端 VAD
                    java.util.Arrays.fill(frame, 0)
                }
                try {
                    dump?.write(frame)
                } catch (_: Exception) {
                }
                onFrame(frame)

                if (meterN >= METER_SAMPLES / 4) {
                    val rms = sqrt(meterSum / meterN)
                    val dbfs = if (rms < 1.0) -99.0 else 20 * log10(rms / 32768.0)
                    onLevel((((dbfs + 55.0) / 40.0)).toFloat().coerceIn(0f, 1f))
                    if (meterN >= METER_SAMPLES) {
                        Log.i(TAG, "上行电平 %.1f dBFS  峰值 %d".format(dbfs, meterPeak))
                        meterSum = 0.0
                        meterN = 0
                        meterPeak = 0
                    }
                }
            }
        }
        return null
    }

    /**
     * 噪声门一帧推进。返回 null = 门开着，帧照常上行；
     * 返回 false = 门关着，调用方须把这一帧静音后再上行。
     * 顺手更新自适应噪底（快速向下跟踪、缓慢向上漂）。
     */
    private fun gateFrame(frameDb: Float): Boolean? {
        lastGateDb = frameDb
        noiseDb = if (frameDb < noiseDb) noiseDb * 0.7f + frameDb * 0.3f
        else minOf(noiseDb + 0.002f, NOISE_FLOOR_MAX_DB)
        noiseDb = noiseDb.coerceIn(NOISE_FLOOR_MIN_DB, NOISE_FLOOR_MAX_DB)

        val speaking = speakingGate()
        val openDb = if (speaking) GATE_SPEAK_DB else GATE_IDLE_DB
        val margin = if (speaking) GATE_SPEAK_MARGIN_DB else GATE_IDLE_MARGIN_DB
        val threshold = maxOf(openDb, noiseDb + margin)
        val attack = if (speaking) GATE_ATTACK_FRAMES_SPEAK else GATE_ATTACK_FRAMES_IDLE
        val release = if (speaking) GATE_RELEASE_FRAMES_SPEAK else GATE_RELEASE_FRAMES_IDLE

        if (gateOpen) {
            gateAbove = 0
            if (frameDb < threshold - GATE_HYSTERESIS_DB) {
                gateBelow++
                if (gateBelow >= release) {
                    gateBelow = 0
                    gateOpen = false
                    Log.i(TAG, "噪声门 关: %.1f dBFS < %.1f，噪底 %.1f，MOSS说话=$speaking".format(frameDb, threshold, noiseDb))
                }
            } else {
                gateBelow = 0
            }
            return null
        }
        gateBelow = 0
        if (frameDb > threshold) {
            gateAbove++
            if (gateAbove >= attack) {
                gateAbove = 0
                gateOpen = true
                Log.i(TAG, "噪声门 开: %.1f dBFS > %.1f，噪底 %.1f，MOSS说话=$speaking，连续 ${attack}帧".format(frameDb, threshold, noiseDb))
            }
            return false          // 门还没开：这帧先静音
        }
        gateAbove = 0
        return false
    }

    /** 立刻把已录内容刷到磁盘（生成笔记前调用，避免缓冲区丢尾部）。 */
    fun flushDump() {
        try {
            dump?.flush()
        } catch (_: Exception) {
        }
    }

    fun release() {
        running = false
        worker?.join(400)
        worker = null
        try { dump?.flush() } catch (_: Exception) {}
        try { dump?.close() } catch (_: Exception) {}
        dump = null
        try { aec?.enabled = false } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.enabled = false } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        aec = null
        ns = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
    }
}
