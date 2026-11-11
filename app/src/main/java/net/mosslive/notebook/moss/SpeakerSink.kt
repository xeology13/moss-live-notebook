package net.mosslive.notebook.moss

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 下行播放：48 kHz 单声道 PCM16。
 * 每个 chunk 播完必须回 playback.chunk.completed，否则服务端判 playback_timeout 掐掉回答。
 */
class SpeakerSink(
    private val dumpPath: String? = null,
    private val onLevel: (Float) -> Unit = {},
    private val onChunkPlayed: (JSONObject) -> Unit,
) {

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val TAG = "SpeakerSink"
    }

    private class Item(val pcm: ByteArray, val ctx: JSONObject?)

    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var dump: java.io.BufferedOutputStream? = null

    @Volatile
    var busy: Boolean = false
        private set

    fun start(): String? {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 10 * 2) // ≥100 ms，压低端到端延迟
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 创建失败", e)
            return "AudioTrack 创建失败: ${e.message}"
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return "AudioTrack 初始化失败 state=${t.state}"
        }
        t.play()
        track = t
        running = true
        Log.i(TAG, "下行已开: 48k mono, buf=$bufSize")
        dumpPath?.let { p ->
            try {
                val f = java.io.File(p)
                f.parentFile?.mkdirs()
                dump = java.io.BufferedOutputStream(java.io.FileOutputStream(f))
                Log.i(TAG, "下行落盘: ${f.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "下行落盘失败: ${e.message}")
            }
        }
        worker = thread(name = "spk-downlink") {
            while (running) {
                val item = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                busy = true
                var off = 0
                while (off < item.pcm.size && running) {
                    val n = t.write(item.pcm, off, item.pcm.size - off)
                    if (n <= 0) break
                    off += n
                }
                busy = false
                try {
                    dump?.write(item.pcm)
                } catch (_: Exception) {
                }
                onLevel(rmsLevel(item.pcm))
                item.ctx?.let(onChunkPlayed)
            }
        }
        return null
    }

    fun push(pcm: ByteArray, ctx: JSONObject?) {
        if (running) queue.offer(Item(pcm, ctx))
    }

    /** 下行音量 → 0..1（-55 dBFS 为 0，-15 dBFS 为 1），驱动界面上的球。 */
    private fun rmsLevel(pcm: ByteArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (pcm.size / 2))
        val db = if (rms < 1) -99.0 else 20 * kotlin.math.log10(rms / 32768.0)
        return ((db + 55.0) / 40.0).toFloat().coerceIn(0f, 1f)
    }

    /** 打断：清空未播内容 + 丢弃硬件缓冲。 */
    fun flush() {
        queue.clear()
        track?.let {
            try {
                it.pause()
                it.flush()
                it.play()
            } catch (_: Exception) {
            }
        }
        busy = false
    }

    /** 立刻把已播内容刷盘（生成笔记前调用）。 */
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
        queue.clear()
        try { dump?.flush() } catch (_: Exception) {}
        try { dump?.close() } catch (_: Exception) {}
        dump = null
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }
}
