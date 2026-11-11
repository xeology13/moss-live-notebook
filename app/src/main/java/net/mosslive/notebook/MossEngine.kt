package net.mosslive.notebook

import android.content.Context
import android.util.Log
import net.mosslive.notebook.moss.MossClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

/**
 * 全局单例：持有 MossClient，向 UI 暴露 StateFlow，并把 MOSS 的工具调用落到本机存储。
 * 生命周期由 SessionService 的前台服务托管。
 */
object MossEngine {

    private const val TAG = "MossEngine"

    private const val PERSONA =
        "你是用户的随身笔记本助手，帮他一边想一边把想法理清楚。" +
            "说话口语、简短，一般一两句；不要念 Markdown、列表或链接。" +
            "他想不清的时候帮他追问一句关键问题，不要长篇说教。不知道就说不知道。" +
            "只讲普通话。"

    private const val INSTRUCTIONS =
        "【语言】用户可能用粤语说话，但你必须一律用普通话回答（口语、简短）：" +
            "绝不说粤语，不写繁体字和粤语字。听不清就追问一句，不要猜。\n" +
            "用户在边走边想，帮他把话说清楚、把关键点收拢。不要主动推销、不要给一长串建议，除非他问。\n" +
            "【笔记】用户说「生成笔记 / 记下来 / 整理成笔记 / 存一篇」时，调用 save_note：" +
            "标题自己起，8-18 字，不要带日期；正文用 Markdown，把小标题和要点整理清楚，" +
            "保留用户的意思、判断和结论，但一律写成普通话书面语 + 简体字" +
            "（禁繁体字与粤语字：嘅 喺 咗 唔 啲 睇 佢 呢 嗰 等）；" +
            "不要编造他没说过的内容。" +
            "调用后一句话告诉他存好了，不要朗读正文。\n" +
            "用户问「我记过什么 / 有哪些笔记」时调用 list_notes；要读某篇用 read_note；" +
            "要改写某篇用 update_note。工具的 id 一律先用 list_notes 查，不要猜。"

    enum class Who { USER, MOSS }

    data class Turn(val who: Who, val text: String)

    data class UiState(
        val running: Boolean = false,
        val status: String = "未开始",
        val turns: List<Turn> = emptyList(),
        val aec: String = "",
        val latency: String = "",
        val error: String? = null,
        val level: Float = 0f,
        val levelFromUser: Boolean = false,
        val notes: List<NoteStore.Note> = emptyList(),
        val toast: String? = null,
        val openNoteId: String? = null,
        val debug: Boolean = false,
        val noteBusy: Boolean = false,
        /** 非空 = 这通是「继续聊」：正在基于一篇已有笔记说话，保存时改写它而不是新建。 */
        val continueTitle: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    data class KeyInfo(val source: String, val masked: String, val hasCustom: Boolean)

    private const val PREFS = "moss"

    // ---------- 设置读写 ----------

    fun resolveKey(context: Context): String {
        val custom = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("api_key", null)
        return if (!custom.isNullOrBlank()) custom.trim() else BuildConfig.MOSS_API_KEY
    }

    fun saveKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("api_key", key.trim()).apply()
    }

    fun clearKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("api_key").apply()
    }

    fun audioOptions(context: Context): Pair<Boolean, Float> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean("ns", true) to sp.getFloat("gain", 1.0f)
    }

    // ---------- 整理笔记用的模型（可选；不配就走本地规则整理） ----------
    //
    // 开源版不内置任何 key：MOSS key 与整理用的模型 key 都由用户在设置页自己填。
    // 每个提供商各存一份 base/model/key，来回切换不会互相覆盖。

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun llmProviderId(context: Context): String =
        prefs(context).getString("llm_provider", null)
            ?.takeIf { LlmProviders.byId(it) != null } ?: LlmProviders.default.id

    /** 指定提供商的配置（设置页切换提供商时用它回填界面）。 */
    fun llmConfigFor(context: Context, providerId: String): LlmConfig {
        val sp = prefs(context)
        val preset = LlmProviders.byId(providerId) ?: LlmProviders.default
        val base = sp.getString("llm_base_${preset.id}", null)?.takeIf { it.isNotBlank() }
            ?: preset.baseUrl
        val model = sp.getString("llm_model_${preset.id}", null)?.takeIf { it.isNotBlank() }
            ?: preset.model
        val saved = sp.getString("llm_key_${preset.id}", null)?.takeIf { it.isNotBlank() }
        // 兼容 0.2.12 及更早：整理 key 当时只有 MiniMax 一家，存在 mmx_key
        val legacy = if (preset.id == LlmProviders.MINIMAX) {
            sp.getString("mmx_key", "").orEmpty().trim()
        } else ""
        return LlmConfig(preset.id, base.trim(), model.trim(), (saved ?: legacy).trim())
    }

    /** 当前生效的整理配置。 */
    fun llmConfig(context: Context): LlmConfig = llmConfigFor(context, llmProviderId(context))

    fun saveLlmConfig(context: Context, providerId: String, baseUrl: String, model: String, key: String) {
        val preset = LlmProviders.byId(providerId) ?: LlmProviders.default
        prefs(context).edit()
            .putString("llm_provider", preset.id)
            .putString("llm_base_${preset.id}", baseUrl.trim())
            .putString("llm_model_${preset.id}", model.trim())
            .putString("llm_key_${preset.id}", key.trim())
            .apply()
    }

    /** 设置页那行状态：提供商 · 模型 · key（只给指纹）。 */
    fun llmInfo(context: Context): String = describeLlm(llmConfig(context))

    fun describeLlm(cfg: LlmConfig): String {
        val label = cfg.provider?.label ?: cfg.providerId
        val key = when {
            cfg.apiKey.isNotBlank() -> LlmProviders.mask(cfg.apiKey)
            LlmProviders.needsKey(cfg.providerId) -> "未填 key（用本地规则整理）"
            else -> "不需要 key"
        }
        return "$label · ${cfg.model.ifBlank { "未填模型" }} · $key"
    }

    fun saveAudioOptions(context: Context, ns: Boolean, gain: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("ns", ns).putFloat("gain", gain).apply()
    }

    fun keyInfo(context: Context): KeyInfo {
        val custom = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("api_key", null)
        val k = if (!custom.isNullOrBlank()) custom.trim() else BuildConfig.MOSS_API_KEY
        val source = when {
            k.isBlank() -> "未配置"
            custom.isNullOrBlank() -> "内置（构建时注入）"
            else -> "自定义（手机上填写）"
        }
        val masked = if (k.length <= 12) "…" else "${k.take(6)}…${k.takeLast(4)}（${k.length} 字符）"
        return KeyInfo(source, masked, !custom.isNullOrBlank())
    }

    // ---------- 笔记 ----------

    fun refreshNotes(context: Context) {
        NoteStore.init(context)
        // 首次运行带入内置「使用说明」（有持久化标记，不会重复塞）
        NoteStore.ensureDefaultNote(context)
        ensureMark(context)
        val dbg = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("debug", false)
        _state.update { it.copy(notes = NoteStore.list(), debug = dbg) }
    }

    fun setDebug(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("debug", on).apply()
        _state.update { it.copy(debug = on) }
    }

    /**
     * 设置页「重新写入使用说明」：只在列表里没有这一条时写回，不覆盖用户改过的版本。
     * @return true = 这次真的写入了
     */
    fun restoreGuideNote(context: Context): Boolean {
        NoteStore.init(context)
        val wrote = NoteStore.ensureDefaultNote(context, force = true)
        refreshNotes(context)
        return wrote
    }

    /** 自测：走一遍 save_note 的真实路径（落盘 + 刷新列表），不需要真通话。 */
    fun selfTestSave(context: Context) {
        appContext = context.applicationContext
        NoteStore.init(context)
        val args = JSONObject()
            .put("title", "测试笔记·语音笔记本自测")
            .put(
                "markdown",
                "## 自测\n\n这篇是由「写入一篇测试笔记」按钮生成的，用来验证：\n\n" +
                    "- Markdown 落盘\n- 列表刷新\n- 打开 / 分享 / 导出\n\n> 时间：" + formatTime(System.currentTimeMillis())
            )
        val out = runTool("save_note", args)
        Log.i(TAG, "自测 save_note → $out")
        refreshNotes(context)
    }

    fun openNote(id: String?) {
        _state.update { it.copy(openNoteId = id) }
    }

    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    private var appContext: Context? = null

    private fun refreshNotes() {
        appContext?.let { refreshNotes(it) }
    }

    // ---------- 会话 ----------

    private var client: MossClient? = null
    private var tUserDone = 0L
    private var tFirstAudio = 0L
    private var currentDump: String? = null
    private var currentDownlink: String? = null
    /** 上一次生成笔记时消耗到的录音位置。持久化，App 重启后也不会重复生成同一段。 */
    private fun defs(context: Context) = context.getSharedPreferences("moss", Context.MODE_PRIVATE)

    private fun loadMark(context: Context): Pair<String, Long>? {
        val p = defs(context)
        val path = p.getString("note_mark_path", null) ?: return null
        return path to p.getLong("note_mark_size", 0L)
    }

    private fun saveMark(context: Context, path: String, size: Long) {
        defs(context).edit().putString("note_mark_path", path)
            .putLong("note_mark_size", size).apply()
    }

    /**
     * 把「这一通已经存过笔记」记到持久化 mark 上（当前这一通的录音路径 + 当前长度）。
     * 工具路径（MOSS 自己调 save_note）必须调用它，否则挂断判定不认这笔笔记。
     */
    private fun markCallConsumed(context: Context) {
        val p = currentDump ?: return
        val f = java.io.File(p)
        if (f.isFile) saveMark(context, p, f.length())
    }

    /**
     * 这一通是否已经存过笔记：按「当前这通录音的文件路径」与上次生成笔记时记下的路径比对。
     * 不用全局布尔——整理是在后台线程跑的，上一通迟到的完成会把新通话误标成「已有笔记」，
     * 导致新通话挂断时不弹窗也不生成（2026-11-08 修）。
     * ⚠️ 不含「生成后又录了多久」这类判定：用户明确要求「界面上说已保存/整理中，就直接能挂断」，
     * 多一条生长判定就多一种误弹窗（2026-11-08 二修，手机上实测过 note_mark 停在旧录音导致的误弹）。
     */
    fun hasNoteThisCall(): Boolean {
        val ctx = appContext ?: return false
        val dump = currentDump ?: return false
        val mark = loadMark(ctx) ?: return false
        return mark.first == dump
    }

    /** 服务端主动断开/用户挂断后通知 Service 收尾 */
    var onFinished: (() -> Unit)? = null

    /** 本通「继续聊」的目标笔记 id；null = 普通通话（保存 = 新建）。挂断即清空。 */
    @Volatile private var continueNoteId: String? = null

    fun setError(msg: String) {
        Log.w(TAG, msg)
        _state.update { it.copy(error = msg) }
    }

    fun start(context: Context, continueId: String? = null) {
        if (client != null) return
        val key = resolveKey(context)
        if (key.isBlank()) {
            setError("未配置 MOSS key：设置页填一个")
            return
        }
        val app = context.applicationContext
        appContext = app
        NoteStore.init(app)
        refreshNotes(app)
        pruneRecordings(app)

        // 继续聊：目标笔记必须真实存在，否则退回普通通话
        val contNote = continueId?.let { NoteStore.read(it) }
        continueNoteId = contNote?.id
        val effectiveInstructions = contNote?.let { n -> INSTRUCTIONS + continueBlock(n) } ?: INSTRUCTIONS

        tUserDone = 0L
        tFirstAudio = 0L
        _state.update {
            it.copy(
                running = true, status = "连接中…", turns = emptyList(), error = null, level = 0f,
                continueTitle = contNote?.title
            )
        }

        val (nsOn, gain) = audioOptions(context)
        val dump = java.io.File(context.filesDir, "uplink-${System.currentTimeMillis()}.pcm").absolutePath
        val dumpDown = java.io.File(context.filesDir, "downlink-${System.currentTimeMillis()}.pcm").absolutePath
        currentDump = dump
        currentDownlink = dumpDown

        val c = MossClient(
            apiKey = key,
            userId = deviceId(context),
            persona = PERSONA,
            instructions = effectiveInstructions,
            enableNs = nsOn,
            gain = gain,
            dumpPath = dump,
            downlinkDumpPath = dumpDown,
            toolHandler = { name, args -> runTool(name, args) },
            listener = object : MossClient.Listener {

                override fun onStatus(text: String) {
                    _state.update { it.copy(status = text) }
                }

                override fun onUserText(text: String) {
                    if (text.isBlank()) return
                    tUserDone = System.currentTimeMillis()
                    _state.update { it.copy(turns = it.turns + Turn(Who.USER, text)) }
                    if (NOTE_TRIGGER.containsMatchIn(text)) {
                        appContext?.let { c -> generateNote(c) }
                    }
                }
                override fun onMossText(text: String) {
                    if (text.isBlank()) return
                    _state.update { it.copy(turns = it.turns + Turn(Who.MOSS, text)) }
                }

                override fun onAudioStarted() {
                    if (tFirstAudio == 0L) {
                        tFirstAudio = System.currentTimeMillis()
                        if (tUserDone > 0) {
                            val ms = tFirstAudio - tUserDone
                            _state.update { it.copy(latency = "说完→出声 ${ms}ms") }
                        }
                    }
                    _state.update { it.copy(status = "MOSS 在说…") }
                }

                override fun onAudioStopped(reason: String?) {
                    _state.update { it.copy(status = "被打断(${reason ?: "-"})") }
                }

                override fun onError(text: String) {
                    setError(text)
                }

                override fun onAecInfo(text: String) {
                    _state.update { it.copy(aec = text) }
                }

                override fun onClosed(reason: String) {
                    client = null
                    continueNoteId = null
                    _state.update {
                        it.copy(running = false, status = "已挂断 · $reason", level = 0f, continueTitle = null)
                    }
                    onFinished?.invoke()
                }

                override fun onLevel(level: Float, fromUser: Boolean) {
                    // 只让"当前在说话的一方"驱动球：MOSS 播放时上行必然有回声，忽略它
                    if (!fromUser && level < 0.02f) return
                    _state.update { it.copy(level = level, levelFromUser = fromUser) }
                }

                override fun onToolCall(name: String, args: JSONObject) {
                    Log.i(TAG, "工具 $name ${args.toString().take(120)}")
                }
            }
        )
        client = c
        c.start()
    }

    fun stop() {
        client?.stop()
        client = null
        continueNoteId = null
        _state.update { it.copy(running = false, level = 0f, continueTitle = null) }
    }

    /**
     * 「继续聊」的会话指令追加块：把原笔记全文喂进上下文，并改写保存语义——
     * 默认改写这一篇（update_note，整篇重写、标题可改），用户明说「另存一篇」才落新文件。
     */
    private fun continueBlock(n: NoteStore.Note): String = "\n" +
        "【继续笔记 · 本块规则覆盖上面【笔记】一节】这通通话是在继续一篇已有笔记。\n" +
        "目标笔记：id=${n.id}，标题《${n.title}》。\n" +
        "原文全文：\n---\n${n.markdown}\n---\n" +
        "用户说「保存 / 记下来 / 整理 / 生成笔记」等时，调用 update_note：id 用上面的 id，" +
        "markdown 写「原文 + 这通新聊内容」整合后的整篇改写版（不是只写新增部分），" +
        "标题可以按新内容调整，其余要求（普通话书面语、简体、不编造）不变。\n" +
        "默认始终改写这一篇；只有用户明确说「另存一篇 / 新建一篇 / 单独记一篇」时，才调用 save_note 落一篇新笔记。\n" +
        "开场不要朗读或复述原文，自然等用户开口；用户提到原文里的内容时你要知道它在说什么。"


    // ---------- 笔记生成（客户端自己干，不依赖 MOSS 的工具） ----------

    private val NOTE_TRIGGER = Regex(
        "生成笔记|写成笔记|成为笔记|整理成笔记|存成笔记|记成笔记|做一篇笔记|笔记存|存一篇|记一篇|" +
            "记一下|记录一下|记下来|帮我记"
    )

    /**
     * 用这一通落盘的上行音频生成笔记：
     *   PCM → WAV → MOSS 批量转写（同一把 key）→ 用户选的模型整理（可选）→ 本地兜底 → 存 md
     */
    fun generateNote(context: Context, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        if (_state.value.noteBusy) return
        // 没有正在进行的通话时，用最近一次的通话录音（方便重跑/手动补生成）
        val dump = currentDump ?: context.filesDir
            .listFiles { f -> f.isFile && f.name.startsWith("uplink-") && f.name.endsWith(".pcm") }
            ?.maxByOrNull { it.lastModified() }?.absolutePath
        appContext = context.applicationContext
        NoteStore.init(context)
        // 继续聊模式下整篇改写目标笔记；进线程前先定死 id，挂断竞态不会改语义
        val contId = continueNoteId
        _state.update { it.copy(noteBusy = true, status = "整理笔记中…", error = null) }

        Thread {
            var ok = false
            var wav: java.io.File? = null
            try {
                // 它还在说的时候直接抓样本，会得到半句话（实测笔记里出现「跌幅数据被
                // 打断，未完整记录」）。等它把这一句说完再落样本，最多等 6 秒。
                val waitUntil = System.currentTimeMillis() + 6000
                while (client?.isSpeaking() == true && System.currentTimeMillis() < waitUntil) {
                    Thread.sleep(120)
                }
                client?.flushDump()
                Thread.sleep(250)
                val src = dump?.let { java.io.File(it) }
                if (src == null || !src.isFile || src.length() < 32_000) {
                    setError("这段还没录到足够的声音，先去说几句")
                } else if (run {
                        // 上次生成笔记之后，这段录音有没有长出新内容（≥2 秒）
                        val mark = loadMark(context)
                        mark != null && mark.first == src.absolutePath &&
                            src.length() - mark.second < 64_000
                    } && !force) {
                    setError("无新的语音记录")
                } else {
                    wav = java.io.File(context.cacheDir, "note-${System.currentTimeMillis()}.wav")
                    MossApi.pcmToWav(src, wav)
                    val asr = MossApi.transcribe(resolveKey(context), wav)
                    if (asr.isNullOrBlank()) {
                        setError("转写失败：MOSS 批量转写没有返回文本")
                    } else {
                        // MOSS 那一侧：正常说完的轮次有它自己推的文本（最准）；
                        // 被打断的半句服务端一条文本都不给（实测 done/updated 均为 0 条），
                        // 只能从下行录音转写补回来 —— 否则「它查到的资料」就丢了。
                        val mossLines = _state.value.turns
                            .filter { it.who == Who.MOSS }
                            .map { it.text.trim() }
                            .filter { it.isNotBlank() }
                        val mossSpeech = transcribeDownlink(context, resolveKey(context))
                        val material = buildString {
                            // 继续聊：把原笔记底稿也喂给整理模型，改写才能「原文+新内容」整合而非只写增量
                            if (contId != null) {
                                NoteStore.read(contId)?.let { orig ->
                                    append("【原笔记底稿（改写时保留其结构与结论，整合下面新聊到的内容）】\n")
                                    append(orig.markdown.trim()).append("\n\n")
                                }
                            }
                            append("【我】\n").append(asr.trim())
                            if (mossSpeech.isNotBlank()) {
                                append("\n\n【Moss · 逐字转写（含被打断的半句）】\n")
                                append(mossSpeech.trim())
                            }
                            if (mossLines.isNotEmpty()) {
                                append("\n\n【Moss · 服务端给的准确原话】\n")
                                mossLines.forEachIndexed { i, t ->
                                    append(i + 1).append(". ").append(t).append('\n')
                                }
                            }
                        }
                        val draft = MossApi.summarize(llmConfig(context), material)
                            ?: MossApi.localDraft(asr, mossLines, mossSpeech)
                        // 继续聊 = 改写原笔记（同 id 同文件，update 内部留 .bak）；普通通话 = 新建
                        val note = if (contId != null) {
                            NoteStore.update(contId, draft.markdown, draft.title)
                        } else {
                            NoteStore.create(draft.title, draft.markdown)
                        }
                        if (note == null) {
                            setError(if (contId != null) "改写失败：原笔记不见了" else "保存失败：存储不可写")
                        } else {
                            saveMark(context, src.absolutePath, src.length())
                            refreshNotes(context)
                            _state.update {
                                it.copy(
                                    toast = if (contId != null) "已改写笔记：${note.title}"
                                    else "笔记已生成：${note.title}"
                                )
                            }
                            ok = true
                        }
                    }
                }
            } catch (e: Exception) {
                setError("整理失败：${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                wav?.delete()
                _state.update {
                    it.copy(noteBusy = false, status = if (ok) (if (contId != null) "笔记已改写" else "笔记已生成") else it.status)
                }
                onDone?.invoke(ok)
            }
        }.start()
    }

    /** 把下行的 48k 录音降采样到 16k 再转写；没有录音就返回空串。 */
    private fun transcribeDownlink(context: Context, mossKey: String): String {
        val path = currentDownlink ?: context.filesDir
            .listFiles { f -> f.isFile && f.name.startsWith("downlink-") && f.name.endsWith(".pcm") }
            ?.maxByOrNull { it.lastModified() }?.absolutePath
            ?: return ""
        val src = java.io.File(path)
        if (!src.isFile || src.length() < 48_000) return ""
        val ts = System.currentTimeMillis()
        val raw16 = java.io.File(context.cacheDir, "dl16-$ts.pcm")
        val wav = java.io.File(context.cacheDir, "dl-$ts.wav")
        return try {
            MossApi.downsample48to16(src, raw16)
            MossApi.pcmToWav(raw16, wav)
            MossApi.transcribe(mossKey, wav) ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "下行转写失败: ${e.message}")
            ""
        } finally {
            raw16.delete()
            wav.delete()
        }
    }

    /** 首次升级上来时，把「已经消化过的录音」标记到最近一段，避免重复生成旧记录。 */
    private fun ensureMark(context: Context) {
        if (loadMark(context) != null) return
        val newest = context.filesDir
            .listFiles { f -> f.isFile && f.name.startsWith("uplink-") && f.name.endsWith(".pcm") }
            ?.maxByOrNull { it.lastModified() } ?: return
        saveMark(context, newest.absolutePath, newest.length())
    }

    /** 清理 3 天前的通话录音，避免占空间。 */
    private fun pruneRecordings(context: Context) {
        val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        context.filesDir.listFiles { f ->
            f.isFile && (f.name.startsWith("uplink-") || f.name.startsWith("downlink-")) &&
                f.name.endsWith(".pcm") && f.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    // ---------- 工具实现（在后台线程调用） ----------
    private fun runTool(name: String, args: JSONObject): String {
        val ctx = appContext ?: return "错误：应用上下文丢失"
        NoteStore.init(ctx)
        return try {
            when (name) {
                "save_note" -> {
                    val title = args.optString("title", "").ifBlank { "未命名笔记" }
                    val md = args.optString("markdown", "")
                    if (md.isBlank()) return "错误：markdown 为空，请重新生成"
                    val note = NoteStore.create(title, md)
                    if (note == null) {
                        "保存失败：存储不可写"
                    } else {
                        // 语音触发时是 MOSS 自己调这个工具写的笔记：必须同时把「这一通已存」
                        // 的标记打上，否则挂断时 hasNoteThisCall() 仍为 false，会再弹一次
                        // 「这通还没存笔记」（2026-11-08 二修，手机上实测复现）。
                        markCallConsumed(ctx)
                        refreshNotes(ctx)
                        _state.update { it.copy(toast = "已保存笔记：${note.title}") }
                        "已保存。id=${note.id}，标题《${note.title}》，正文 ${note.body.length} 字。"
                    }
                }

                "list_notes" -> {
                    val notes = NoteStore.list()
                    if (notes.isEmpty()) "用户还没有任何笔记。"
                    else notes.take(20).joinToString("\n") { n ->
                        "- id=${n.id} | ${n.title} | ${formatTime(n.updatedAt)}"
                    }
                }

                "read_note" -> {
                    val id = args.optString("id")
                    val n = NoteStore.read(id)
                    if (n == null) "找不到 id=$id 的笔记，请先用 list_notes 查 id。"
                    else "《${n.title}》（${formatTime(n.updatedAt)}）\n\n${n.body}"
                }

                "update_note" -> {
                    val id = args.optString("id")
                    val md = args.optString("markdown", "")
                    if (md.isBlank()) return "错误：markdown 为空"
                    val n = NoteStore.update(id, md)
                    if (n == null) {
                        "找不到 id=$id 的笔记，未修改。"
                    } else {
                        // 改写也算这一通存过笔记，挂断判定与 save_note 同权
                        markCallConsumed(ctx)
                        refreshNotes(ctx)
                        _state.update { it.copy(toast = "已更新笔记：${n.title}") }
                        "已更新《${n.title}》。"
                    }
                }

                else -> "未知工具：$name"
            }
        } catch (e: Exception) {
            Log.w(TAG, "工具异常 $name", e)
            "执行失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ts))

    private fun deviceId(context: Context): String {
        val sp = context.getSharedPreferences("moss", Context.MODE_PRIVATE)
        var id = sp.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = "nb-" + UUID.randomUUID().toString().take(8)
            sp.edit().putString("device_id", id).apply()
        }
        return id
    }
}
