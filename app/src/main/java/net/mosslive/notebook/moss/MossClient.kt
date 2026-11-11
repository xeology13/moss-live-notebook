package net.mosslive.notebook.moss

import net.mosslive.notebook.BuildConfig

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MOSS-Live 实时双工客户端（M0）。
 *
 * 协议（已由本机 Python 客户端实测）：
 *   wss://api.mosi.cn/v1/realtime?model=moss-live-1.0&user=<id>
 *   握手头 Authorization: Bearer <key>
 *   上行 16k mono PCM16，20ms/帧；下行 48k mono PCM16
 *   response.audio.context(chunk_id/run_id/turn_id) → 二进制 chunk → 播完回 playback.chunk.completed
 *   response.audio.stop → flush + playback.stopped
 */
class MossClient(
    private val apiKey: String,
    private val userId: String,
    private val persona: String,
    private val instructions: String,
    private val enableNs: Boolean = false,
    private val gain: Float = 1.0f,
    private val dumpPath: String? = null,
    private val downlinkDumpPath: String? = null,
    private val toolHandler: (String, JSONObject) -> String = { _, _ -> "工具未实现" },
    private val listener: Listener,
) {

    interface Listener {
        fun onStatus(text: String)
        fun onUserText(text: String)
        fun onMossText(text: String)
        fun onAudioStarted()
        fun onAudioStopped(reason: String?)
        fun onError(text: String)
        fun onAecInfo(text: String)
        fun onClosed(reason: String)
        fun onLevel(level: Float, fromUser: Boolean)
        fun onToolCall(name: String, args: JSONObject)
    }

    companion object {
        private const val TAG = "MossClient"
        private const val BASE_URL = "wss://api.mosi.cn/v1/realtime?model=moss-live-1.0"
        private const val TX_GAIN = 1.0f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var speaker: SpeakerSink? = null
    @Volatile private var mic: MicSource? = null
    @Volatile private var pendingContext: JSONObject? = null
    @Volatile private var audioStarted = false
    @Volatile private var stopped = false
    private var chunkCount = 0
    @Volatile private var tTranscript = 0L
    @Volatile private var chunkLogged = false
    @Volatile private var partialOut = ""
    /** MOSS 正在播报（下行事件驱动）：MicSource 噪声门在播报期间用更严的阈值。 */
    @Volatile private var mossSpeaking = false

    fun start() {
        listener.onStatus("连接中…")
        val req = Request.Builder()
            .url("$BASE_URL&user=$userId")
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = http.newWebSocket(req, WsListener())
    }

    fun flushDump() {
        mic?.flushDump()
        speaker?.flushDump()
    }

    /** 它现在是否还在出声（生成笔记前等它把这一句说完）。 */
    fun isSpeaking(): Boolean = speaker?.busy == true

    fun stop(reason: String = "用户挂断") {
        if (stopped) return
        stopped = true
        mossSpeaking = false
        mic?.release()
        mic = null
        speaker?.release()
        speaker = null

        // ⚠️ 必须先把队列播完的回执结掉，服务端才会立刻释放并发槽；
        // 只 close 不回 playback.completed，会占着槽到服务端超时（实测约 7 分钟，之后新连接全 429）。
        val w = ws
        ws = null
        if (w != null) {
            try {
                w.send(JSONObject().put("action", "playback.completed").toString())
            } catch (_: Exception) {
            }
            Thread {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                }
                w.close(1000, "bye")
            }.start()
        }

        listener.onStatus("已挂断")
        listener.onClosed(reason)
    }

    private fun sendJson(o: JSONObject) {
        ws?.send(o.toString())
    }

    private fun startAudio() {
        if (audioStarted) return
        audioStarted = true

        val spk = SpeakerSink(
            dumpPath = downlinkDumpPath,
            onLevel = { listener.onLevel(it, false) }
        ) { ctx ->
            sendJson(
                JSONObject()
                    .put("action", "playback.chunk.completed")
                    .put("chunk_id", ctx.opt("chunk_id") ?: JSONObject.NULL)
                    .put("run_id", ctx.opt("run_id") ?: JSONObject.NULL)
                    .put("turn_id", ctx.opt("turn_id") ?: JSONObject.NULL)
            )
        }
        spk.start()?.let { listener.onError("扬声器: $it") }
        speaker = spk

        val tMic0 = System.currentTimeMillis()
        var frames = 0
        val m = MicSource(
            gain = gain,
            enableNs = enableNs,
            dumpPath = dumpPath,
            speakingGate = { mossSpeaking },
            onLevel = { listener.onLevel(it, true) }
        ) { pcm ->
            ws?.send(pcm.toByteString())
            frames++
            if (frames % 250 == 0) {
                val ms = System.currentTimeMillis() - tMic0
                // 250 帧 = 5 s 音频；若 ms 明显偏离 5000，说明采集或发送被拖慢
                Log.i(TAG, "上行 $frames 帧 / 实耗 ${ms}ms（理想 ${frames * 20}ms）")
            }
        }
        val micErr = m.start()
        if (micErr != null) {
            listener.onError("麦克风: $micErr")
        } else {
            mic = m
            listener.onAecInfo(m.aecState)
        }

        sendJson(JSONObject().put("action", "mic_state").put("enabled", true))
        listener.onStatus("通话中")
    }

    private fun sessionUpdate(): JSONObject = JSONObject().put("action", "session.update").put(
        "session",
        JSONObject()
            .put(
                "persona",
                JSONObject()
                    .put("name", "Moss")
                    .put("aliases", org.json.JSONArray().put("Moss").put("莫斯"))
                    .put("prompt", persona)
            )
            .put("instructions", instructions)
            .put("tools", toolsJson())
    )

    private fun toolsJson(): org.json.JSONArray {
        fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
            JSONObject()
                .put("type", "function")
                .put("name", name)
                .put("description", desc)
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", org.json.JSONArray(required))
                )

        val arr = org.json.JSONArray()
        arr.put(
            tool(
                "save_note",
                "把刚才的对话整理成一篇 Markdown 笔记保存到用户手机的笔记里。" +
                    "只有在用户明确要求「生成笔记 / 记下来 / 存一篇」时才调用。",
                JSONObject()
                    .put("title", JSONObject().put("type", "string").put("description", "简短标题，8-18 字，不要带日期"))
                    .put(
                        "markdown",
                        JSONObject().put("type", "string")
                            .put("description", "笔记正文，Markdown，一律用普通话书面语 + 简体字（禁繁体字与粤语字）。用小标题和要点把讨论整理清楚，保留用户的意思与结论，不要编造。")
                    ),
                listOf("title", "markdown")
            )
        )
        arr.put(
            tool(
                "list_notes",
                "列出用户已有的笔记（标题+时间）。用户问「我记过什么 / 有哪些笔记」时调用。",
                JSONObject(), emptyList()
            )
        )
        arr.put(
            tool(
                "read_note",
                "读取一篇笔记的全文。需要按 id 取，id 从 list_notes 或界面获得。",
                JSONObject().put("id", JSONObject().put("type", "string").put("description", "笔记 id")),
                listOf("id")
            )
        )
        arr.put(
            tool(
                "update_note",
                "用新的正文覆盖一篇已有笔记，用于「把那篇改一下 / 改短 / 重写」。",
                JSONObject()
                    .put("id", JSONObject().put("type", "string").put("description", "笔记 id"))
                    .put("markdown", JSONObject().put("type", "string").put("description", "新的笔记正文（Markdown 全文）")),
                listOf("id", "markdown")
            )
        )
        return arr
    }

    private inner class WsListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener.onStatus("已连接，建立会话…")
            sendJson(JSONObject().put("action", "attach_session"))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val spk = speaker
            val ctx = pendingContext
            pendingContext = null
            chunkCount++
            if (tTranscript > 0 && !chunkLogged) {
                chunkLogged = true
                Log.i(TAG, "说完→首包 ${System.currentTimeMillis() - tTranscript}ms")
            }
            if (chunkCount == 1 || chunkCount % 100 == 0) {
                Log.i(TAG, "音频 chunk #$chunkCount ${bytes.size}B ackCtx=${ctx != null}")
            }
            if (spk == null) return
            spk.push(bytes.toByteArray(), ctx)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stopped) return
            val ev = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.w(TAG, "非 JSON 消息: ${text.take(120)}")
                return
            }
            val action = ev.optString("action")
            val data = ev.optJSONObject("data") ?: JSONObject()
            when (action) {
                "session_attached" -> {
                    listener.onStatus("会话 ${data.optString("session_id").take(8)}")
                    sendJson(sessionUpdate())
                    // 兜底：万一 session.updated 事件名与预期不同，300ms 后也把音频拉起来
                    scope.launch {
                        delay(300)
                        if (!stopped && !audioStarted) startAudio()
                    }
                }
                "session.updated" -> startAudio()
                "input.speech.started" -> listener.onStatus("在听…")
                "input.speech.stopped" -> listener.onStatus("…")
                "input.transcript.done" -> {
                    tTranscript = System.currentTimeMillis()
                    chunkLogged = false
                    if (BuildConfig.DEBUG) Log.i(TAG, "听到: ${data.optString("text")}")
                    listener.onUserText(data.optString("text"))
                }
                "output.transcript.updated" -> {
                    // 增量文本：只在被打断时当兜底用（正常走 done）
                    val t = data.optString("text")
                    if (t.isNotBlank()) partialOut = t
                }
                "output.transcript.done" -> {
                    partialOut = ""
                    if (BuildConfig.DEBUG) Log.i(TAG, "MOSS: ${data.optString("text")}")
                    listener.onMossText(data.optString("text"))
                }
                "response.audio.context" -> pendingContext = data
                "response.function_call_arguments.done" -> {
                    val name = data.optString("name")
                    val callId = data.optString("call_id")
                    val args = runCatching { JSONObject(data.optString("arguments", "{}")) }
                        .getOrDefault(JSONObject())
                    Log.i(TAG, "工具调用 $name ${data.optString("arguments").take(160)}")
                    listener.onToolCall(name, args)
                    Thread {
                        val out = runCatching { toolHandler(name, args) }
                            .getOrElse { "执行失败：${it.javaClass.simpleName} ${it.message ?: ""}" }
                        Log.i(TAG, "工具返回 $name → ${out.take(200)}")
                        sendJson(
                            JSONObject().put("action", "conversation.item.create").put(
                                "item",
                                JSONObject()
                                    .put("type", "function_call_output")
                                    .put("call_id", callId)
                                    .put("output", out)
                            )
                        )
                    }.start()
                }
                "response.audio.started" -> {
                    mossSpeaking = true
                    listener.onAudioStarted()
                }
                "response.audio.stop", "response.interrupted", "response.audio.interrupted" -> {
                    mossSpeaking = false
                    speaker?.flush()
                    pendingContext = null
                    // 注意：服务端不认 "playback.stopped"（会回 invalid_public_request），
                    // 打断只做本地清队列 —— 与生产版 Python 客户端一致。
                    // 被打断的那句不会有 output.transcript.done，用最后一次增量文本兜底，
                    // 否则「MOSS 查到的资料」会整段丢失。
                    if (partialOut.isNotBlank()) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "MOSS(被打断): $partialOut")
                        listener.onMossText(partialOut.trim() + "（被打断，未说完）")
                        partialOut = ""
                    }
                    val r = data.optString("reason")
                    listener.onAudioStopped(if (r.isBlank()) action else r)
                }
                "response.ended" -> {
                    mossSpeaking = false
                    partialOut = ""
                }
                "error" -> listener.onError(data.toString())
                else -> Log.d(TAG, "event: $action")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            if (response?.code == 429) {
                listener.onError("MOSS 并发上限（每账号 1 路）：上一个会话还没释放，等约 1 分钟再拨")
                stop("并发上限 429")
                return
            }
            val why = "连接失败: ${t.javaClass.simpleName} ${t.message ?: ""} http=${response?.code ?: "-"}"
            listener.onError(why)
            stop("网络错误")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!stopped) {
                listener.onStatus("服务端关闭($code)")
                stop("服务端关闭")
            }
        }
    }
}
