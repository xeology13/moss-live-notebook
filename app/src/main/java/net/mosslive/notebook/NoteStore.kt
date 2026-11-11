package net.mosslive.notebook

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记存储：一篇笔记 = 一个 Markdown 文件。
 *
 *   files/notes/<id>.md
 *   内容：第一行 "# 标题"，接着是 front matter 风格的时间行，然后正文
 *
 * 不用数据库索引：文件名 + 文件头就是全部元数据，不会出现「索引和文件不一致」。
 */
object NoteStore {

    private const val TAG = "NoteStore"

    /** 内置说明是否已塞过的标记（与笔记文件分开放，避免和 moss.xml 的设置混在一起）。 */
    private const val SEED_PREFS = "note_seed"
    private const val KEY_GUIDE = "guide_note_seeded"

    data class Note(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val body: String,
    ) {
        val preview: String
            get() = body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .firstOrNull() ?: ""

        /** 整篇 Markdown：`# 标题` + 空行 + 正文。阅读、复制、分享文本都用这一个真源。 */
        val markdown: String
            get() = buildString {
                if (title.isNotBlank()) append("# ").append(title).append("\n\n")
                append(body.trim())
            }
    }

    /**
     * 把整篇 Markdown 拆回 (标题, 正文)：第一个非空行是 `# X` 就取它当标题，
     * 否则沿用 [fallbackTitle]（用户把标题行删掉时不会丢标题）。
     */
    fun splitMarkdown(md: String, fallbackTitle: String = ""): Pair<String, String> {
        val lines = md.replace("\r\n", "\n").lines()
        val head = lines.indexOfFirst { it.isNotBlank() }
        if (head >= 0 && lines[head].trimStart().startsWith("# ")) {
            val t = lines[head].trimStart().removePrefix("# ").trim()
            val body = lines.drop(head + 1).joinToString("\n").trim()
            return (t.ifBlank { fallbackTitle }) to body
        }
        return fallbackTitle to md.trim()
    }

    private lateinit var dir: File
    private val lock = Any()

    /** 本进程第一次 init 时算出的「是否全新安装」：当时 notes 目录还不存在。 */
    private var freshInstall = false

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        synchronized(lock) {
            if (::dir.isInitialized) return
            val target = File(ctx.filesDir, "notes")
            // 必须在 mkdirs 之前判：升级安装的笔记目录本来就在，只有全新安装才没有
            freshInstall = !target.exists()
            dir = target.apply { mkdirs() }
        }
    }

    /**
     * 内置「使用说明」笔记：只给全新安装，塞一次。
     *
     * - 判据是持久化标记，不是「笔记目录为空」：后者会让用户删掉说明后每次启动又被塞回来。
     * - 升级安装（notes 目录已存在）不塞，只记标记 —— 老用户不需要再收到一条说明。
     * - [force] 供设置页「重新写入使用说明」：跳过上面两条，但**不覆盖**已存在的那一条。
     *
     * @return 是否真的写入了新文件
     */
    fun ensureDefaultNote(ctx: Context, force: Boolean = false): Boolean {
        if (!::dir.isInitialized) return false
        val prefs = ctx.getSharedPreferences(SEED_PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getInt(KEY_GUIDE, 0) >= DefaultNotes.SEEDED) return false
        synchronized(lock) {
            if (!force && prefs.getInt(KEY_GUIDE, 0) >= DefaultNotes.SEEDED) return false
            val file = File(dir, "${DefaultNotes.GUIDE_ID}.md")
            if (file.isFile) {
                // 已经有这条（可能被用户改过）→ 不覆盖、不重复
                prefs.edit().putInt(KEY_GUIDE, DefaultNotes.SEEDED).apply()
                return false
            }
            if (!force && !freshInstall) {
                Log.i(TAG, "笔记目录已存在，判定为升级安装，不写入内置说明")
                prefs.edit().putInt(KEY_GUIDE, DefaultNotes.SEEDED).apply()
                return false
            }
            val ok = runCatching { writeGuide(ctx, file) }.isSuccess
            if (!ok) {
                // 写失败不置标记，留机会下次启动重试
                Log.w(TAG, "内置说明写入失败，下次启动重试")
                return false
            }
            Log.i(TAG, "已写入内置说明笔记 ${file.name}")
            prefs.edit().putInt(KEY_GUIDE, DefaultNotes.SEEDED).apply()
            return true
        }
    }

    private fun writeGuide(ctx: Context, file: File) {
        val body = ctx.assets.open(DefaultNotes.GUIDE_ASSET)
            .bufferedReader().use { it.readText() }
            .replace("\r\n", "\n")
            .trim()
        val now = System.currentTimeMillis()
        file.writeText(
            buildString {
                append("# ").append(DefaultNotes.GUIDE_TITLE).append('\n')
                append("<!-- created: ").append(stamp(now)).append(" -->\n\n")
                append(body).append('\n')
            }
        )
        // 列表按 updatedAt 排序，说明排在首次启动那天的顶部
        file.setLastModified(now)
    }

    private fun stamp(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))

    private fun idStamp(ts: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date(ts))

    /** 只接受我们自己生成的文件名，挡住 "../" 之类的路径穿越。 */
    private fun safeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")

    private fun slug(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").trim('-')
        return cleaned.take(28).ifBlank { "note" }
    }

    fun list(): List<Note> {
        if (!::dir.isInitialized) return emptyList()
        val out = mutableListOf<Note>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
            runCatching { parse(f) }.getOrNull()?.let { out += it }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun read(id: String): Note? {
        if (!::dir.isInitialized) return null
        if (!safeId(id)) return null
        val f = File(dir, "$id.md")
        if (!f.isFile) return null
        return runCatching { parse(f) }.getOrNull()
    }

    /** 新建一篇，返回它的 Note（id 由时间戳+标题组成，可直接用于后续读写）。 */
    fun create(title: String, body: String): Note? {
        if (!::dir.isInitialized) return null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val t = title.trim().ifBlank { "未命名笔记" }
            var id = "${idStamp(now)}-${slug(t)}"
            var n = 1
            while (File(dir, "$id.md").exists()) {
                id = "${idStamp(now)}-${slug(t)}-$n"
                n++
            }
            val file = File(dir, "$id.md")
            val text = buildString {
                append("# ").append(t).append('\n')
                append("<!-- created: ").append(stamp(now)).append(" -->\n\n")
                append(body.trim()).append('\n')
            }
            file.writeText(text)
            return Note(id, t, now, now, body.trim())
        }
    }

    /** 覆盖正文（标题留在文件首行）。改写前把旧版留成 <id>.md.bak，防手滑（list() 不收 .bak，不污染列表）。 */
    fun update(id: String, body: String, newTitle: String? = null): Note? {
        if (!::dir.isInitialized) return null
        if (!safeId(id)) return null
        synchronized(lock) {
            val f = File(dir, "$id.md")
            if (!f.isFile) return null
            val old = runCatching { parse(f) }.getOrNull() ?: return null
            runCatching { File(dir, "$id.md.bak").writeText(f.readText()) }
            val title = newTitle?.trim()?.ifBlank { null } ?: old.title
            val now = System.currentTimeMillis()
            val text = buildString {
                append("# ").append(title).append('\n')
                append("<!-- created: ").append(stamp(old.createdAt)).append(" -->\n\n")
                append(body.trim()).append('\n')
            }
            f.writeText(text)
            return Note(id, title, old.createdAt, now, body.trim())
        }
    }

    fun delete(id: String): Boolean {
        if (!::dir.isInitialized) return false
        if (!safeId(id)) return false
        synchronized(lock) { return File(dir, "$id.md").delete() }
    }

    fun fileFor(id: String): File? {
        if (!::dir.isInitialized) return null
        if (!safeId(id)) return null
        val f = File(dir, "$id.md")
        return if (f.isFile) f else null
    }

    fun dirPath(): String = if (::dir.isInitialized) dir.absolutePath else "-"

    private fun parse(f: File): Note {
        val raw = f.readText()
        val lines = raw.lines()
        val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: f.nameWithoutExtension
        val created = Regex("<!--\\s*created:\\s*([0-9:\\- ]+)")
            .find(raw)?.groupValues?.getOrNull(1)
            ?.let { runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(it.trim())?.time }.getOrNull() }
            ?: f.lastModified()
        val body = lines.drop(1)
            .dropWhile { it.isBlank() || it.startsWith("<!--") }
            .joinToString("\n").trim()
        return Note(f.nameWithoutExtension, title, created, f.lastModified(), body)
    }
}
