package net.mosslive.notebook

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 笔记整理用的接口（都是实测验过的）：
 *
 *  1) MOSS 批量转写  POST https://api.mosi.cn/v1/audio/transcriptions
 *     multipart: file + model=moss-transcribe-diarize-pro  → {"text": "..."}
 *     用同一把 MOSS key。它比 MOSS-Live 的实时听写准得多。
 *
 *  2) 文本整理      POST {baseUrl}/chat/completions (OpenAI 兼容)
 *     提供商与模型由用户在设置页选（见 [LlmProviders]）；没配 key 就走本地规则整理。
 */
object MossApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val MOSS_BASE = "https://api.mosi.cn"
    const val ASR_MODEL = "moss-transcribe-diarize-pro"

    /** MiniMax 的默认模型与备用模型（仅这个提供商自带兜底）。 */
    const val MMX_MODEL = "MiniMax-M3"
    const val MMX_MODEL_FALLBACK = "MiniMax-M3.1-Flash"

    /** 把落盘的裸 PCM（16k mono s16le）包成 WAV，接口只认带头的音频文件。 */
    fun pcmToWav(pcm: File, wav: File, sampleRate: Int = 16000, channels: Int = 1) {
        val dataLen = pcm.length().toInt()
        val byteRate = sampleRate * channels * 2
        FileOutputStream(wav).use { out ->
            val h = ByteArray(44)
            fun putStr(off: Int, s: String) {
                for (i in s.indices) h[off + i] = s[i].code.toByte()
            }
            fun putInt(off: Int, v: Int) {
                h[off] = (v and 0xFF).toByte()
                h[off + 1] = ((v shr 8) and 0xFF).toByte()
                h[off + 2] = ((v shr 16) and 0xFF).toByte()
                h[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun putShort(off: Int, v: Int) {
                h[off] = (v and 0xFF).toByte()
                h[off + 1] = ((v shr 8) and 0xFF).toByte()
            }
            putStr(0, "RIFF")
            putInt(4, 36 + dataLen)
            putStr(8, "WAVE")
            putStr(12, "fmt ")
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, channels)
            putInt(24, sampleRate)
            putInt(28, byteRate)
            putShort(32, channels * 2)
            putShort(34, 16)
            putStr(36, "data")
            putInt(40, dataLen)
            out.write(h)
            pcm.inputStream().use { it.copyTo(out) }
        }
    }

    /** 48k mono s16le → 16k mono s16le（每 3 个样本取平均，够 ASR 用且不炸内存）。 */
    fun downsample48to16(src: File, dst: File) {
        val inS = java.io.BufferedInputStream(src.inputStream())
        try {
            java.io.BufferedOutputStream(java.io.FileOutputStream(dst)).use { out ->
                val block = ByteArray(9600) // 100 ms @48k，能被 6 整除
                var carry = ByteArray(0)
                while (true) {
                    val n = inS.read(block)
                    if (n <= 0) break
                    val buf = if (carry.isNotEmpty()) carry + block.copyOf(n) else block.copyOf(n)
                    val usable = (buf.size / 6) * 6
                    val resampled = ByteArray(usable / 3)
                    var oi = 0
                    var i = 0
                    while (i < usable) {
                        val s = (le16(buf, i) + le16(buf, i + 2) + le16(buf, i + 4)) / 3
                        resampled[oi] = (s and 0xFF).toByte()
                        resampled[oi + 1] = ((s shr 8) and 0xFF).toByte()
                        oi += 2
                        i += 6
                    }
                    out.write(resampled, 0, oi)
                    carry = if (usable < buf.size) buf.copyOfRange(usable, buf.size) else ByteArray(0)
                }
            }
        } finally {
            try { inS.close() } catch (_: Exception) {}
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)).toShort().toInt()

    /** MOSS 批量转写。失败返回 null。 */
    fun transcribe(mossKey: String, wav: File): String? {
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", wav.name,
                    wav.asRequestBody("application/octet-stream".toMediaType())
                )
                .addFormDataPart("model", ASR_MODEL)
                .build()
            val req = Request.Builder()
                .url("$MOSS_BASE/v1/audio/transcriptions")
                .header("Authorization", "Bearer $mossKey")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    android.util.Log.w("MossApi", "ASR ${resp.code}: ${text.take(200)}")
                    return null
                }
                val t = JSONObject(text).optString("text").trim()
                if (t.isBlank()) {
                    android.util.Log.w("MossApi", "ASR 空文本 http=${resp.code} len=${text.length}: ${text.take(240)}")
                }
                t.ifBlank { null }
            }
        } catch (e: Exception) {
            android.util.Log.w("MossApi", "ASR 异常: ${e.message}")
            null
        }
    }

    data class NoteDraft(val title: String, val markdown: String)

    private const val SYS_PROMPT =
        "你是笔记整理助手。给你的素材是一次语音对话的两部分：\n" +
            "【我】用户口述的逐字转写（可能有错别字、断句错误、识别噪声）；\n" +
            "【Moss】AI 助手在通话里说出口的原话，可能包含它查到的资料、数据、结论。\n" +
            "把它整理成一篇 Markdown 笔记。硬要求：\n" +
            "0. 【语言】无论素材是粤语还是普通话，输出一律用普通话书面语 + 简体字。" +
            "禁止繁体字与粤语字（嘅、喺、咗、唔、啲、睇、佢、呢、嗰、攞 等）；" +
            "遇到粤语转写先理解意思，再用普通话写出来。意思、判断、结论、数字不得改动。\n" +
            "1. 不编造、不补充素材之外的内容。\n" +
            "2. 用户自己的观点是主干；Moss 提供的资料/数据/结论要保留，放进对应小节或单独的「## 参考信息」，" +
            "数字、时间、结论一字不改。\n" +
            "3. 修正转写错别字与断句，删掉口语废话（嗯、那个、然后）。\n" +
            "4. 第一行必须是 `TITLE: 标题`（8-18 字，不带日期），空一行后写正文。\n" +
            "5. 正文用 2-4 个 `## 小节` 组织（例如「## 结论」「## 数据」「## 待确认」），" +
            "每个小节下用 `- ` 列要点；即使素材很短也至少一个小节 + 要点列表。\n" +
            "6. 正文 200-800 字，宁短不长；Moss 里标了「被打断」的半句话不要当结论，写进「待确认」。"

    /**
     * 用文本模型整理。提供商、base URL、模型名都来自用户设置（见 [LlmProviders]），
     * 请求体按 OpenAI 兼容格式发；只有 MiniMax 额外带 `thinking` 字段
     * （实测同一段素材：M3 + thinking disabled ≈ 2.4–3.1 s，M3.1-Flash + adaptive ≈ 11 s）。
     */
    fun summarize(cfg: LlmConfig, material: String): NoteDraft? {
        if (!cfg.usable || material.isBlank()) return null
        val raw = callChat(cfg, cfg.model, material)
            ?: if (cfg.providerId == LlmProviders.MINIMAX &&
                cfg.model.equals(MMX_MODEL, ignoreCase = true)
            ) {
                // MiniMax 自家备用模型：主模型下线时同一把 key 还能跑
                callChat(cfg, MMX_MODEL_FALLBACK, material)
            } else {
                null
            }
        if (raw.isNullOrBlank()) return null
        return parseDraft(stripThink(raw), material)
    }

    private fun callChat(cfg: LlmConfig, model: String, material: String): String? {
        val isMiniMax = cfg.providerId == LlmProviders.MINIMAX
        val thinking = if (isMiniMax && !model.equals(MMX_MODEL, ignoreCase = true)) "adaptive" else "disabled"
        return try {
            val url = chatUrl(cfg.baseUrl)
            val payload = JSONObject()
                .put("model", model)
                .put("max_tokens", if (thinking == "adaptive") 3000 else 2000)
                .put("temperature", 0.3)
                .put(
                    "messages", org.json.JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYS_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", material.take(20000)))
                )
            if (isMiniMax) {
                payload.put("thinking", JSONObject().put("type", thinking))
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .apply {
                    if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}")
                    // OpenRouter 用这两个头做来源统计；不带也能用
                    if (cfg.providerId == "openrouter") header("X-Title", "MOSS Notebook")
                }
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    android.util.Log.w("MossApi", "整理[${cfg.providerId}/$model] ${resp.code}: ${body.take(200)}")
                    return null
                }
                JSONObject(body)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            android.util.Log.w("MossApi", "整理[${cfg.providerId}/$model] 异常: ${e.message}")
            null
        }
    }

    /** base URL 末尾有没有 /chat/completions 都认：用户可能直接粘完整 endpoint。 */
    private fun chatUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun stripThink(s: String): String =
        s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    private fun parseDraft(text: String, fallbackSource: String): NoteDraft {
        val lines = text.lines()
        var title = ""
        var bodyStart = 0
        for ((i, line) in lines.withIndex()) {
            val t = line.trim()
            if (t.startsWith("TITLE:", ignoreCase = true)) {
                title = t.substringAfter(":").trim().trim('#').trim()
                bodyStart = i + 1
                break
            }
            if (t.startsWith("# ")) {
                title = t.removePrefix("# ").trim()
                bodyStart = i + 1
                break
            }
        }
        val body = lines.drop(bodyStart).joinToString("\n").trim()
            .ifBlank { text.trim() }
        return NoteDraft(title.ifBlank { fallbackTitle(fallbackSource) }, body)
    }

    /** 没有文本模型时的兜底：我的一侧按句分段，Moss 说的原话/转写单列一节。 */
    fun localDraft(
        transcript: String,
        mossLines: List<String> = emptyList(),
        mossSpeech: String = "",
    ): NoteDraft {
        val clean = transcript.replace(Regex("\\s+"), " ").trim()
        val mine = clean
            .split(Regex("(?<=[。！？!?；;])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "- $it" }
        val body = buildString {
            append("## 我说的\n\n").append(mine)
            if (mossSpeech.isNotBlank()) {
                append("\n\n## Moss 说的（转写）\n\n")
                mossSpeech.replace(Regex("\\s+"), " ").trim()
                    .split(Regex("(?<=[。！？!?；;])"))
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { append("- ").append(it).append('\n') }
            }
            if (mossLines.isNotEmpty()) {
                append("\n\n## Moss 的原话\n\n")
                mossLines.forEach { append("- ").append(it.replace(Regex("\\s+"), " ").trim()).append('\n') }
            }
            append("\n## 原文\n\n").append(clean).append('\n')
        }
        return NoteDraft(fallbackTitle(clean), body)
    }

    private fun fallbackTitle(source: String): String {
        var s = source.replace(Regex("[\\s\\p{Punct}]+"), "").trim()
        s = s.replace(Regex("^(你)?(帮我|帮忙|麻烦你|麻烦|请|给我)+"), "")
        s = s.replace(Regex("^(查一下|看一下|问一下|说说)+"), "")
        return s.take(16).ifBlank { "语音笔记" }
    }
}
