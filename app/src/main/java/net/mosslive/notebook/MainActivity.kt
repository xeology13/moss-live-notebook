package net.mosslive.notebook

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

private val BG = Color(0xFFF7F7F5)
private val INK = Color(0xFF1A1A1A)
private val MUTED = Color(0xFF8A8A85)
private val ACCENT = Color(0xFF2F6D4F)
private val CARD = Color(0xFFFFFFFF)
private val WARN_BG = Color(0xFFFFE9E6)
private val WARN_INK = Color(0xFF8C2B1E)

class MainActivity : ComponentActivity() {

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) startCall() else MossEngine.setError("需要麦克风权限才能通话")
    }

    /** 继续聊的目标笔记 id：权限弹窗绕一圈回来后还要记得从哪篇继续。 */
    private var pendingContinueId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by MossEngine.state.collectAsState()
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var askSaveNote by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(Unit) { MossEngine.refreshNotes(this@MainActivity) }
            BackHandler(enabled = showSettings) { showSettings = false }
            BackHandler(enabled = !showSettings && state.openNoteId != null) {
                MossEngine.openNote(null)
            }
            LaunchedEffect(state.toast) {
                if (state.toast != null) {
                    delay(2600)
                    MossEngine.clearToast()
                }
            }

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = ACCENT, background = BG, surface = BG,
                    onBackground = INK, onSurface = INK
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = BG) {
                    val openId = state.openNoteId
                    when {
                        showSettings -> {
                            val info = remember(showSettings) { MossEngine.keyInfo(this@MainActivity) }
                            val opts = remember(showSettings) { MossEngine.audioOptions(this@MainActivity) }
                            val llm = remember(showSettings) { MossEngine.llmConfig(this@MainActivity) }
                            SettingsScreen(
                                info = info,
                                llm = llm,
                                llmInfo = MossEngine.describeLlm(llm),
                                nsOn = opts.first,
                                gain = opts.second,
                                debug = state.debug,
                                onDebugChange = { MossEngine.setDebug(this@MainActivity, it) },
                                onSelfTest = { MossEngine.selfTestSave(this@MainActivity) },
                                onRestoreGuide = {
                                    val wrote = MossEngine.restoreGuideNote(this@MainActivity)
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (wrote) "已重新写入《使用说明》" else "《使用说明》已在笔记列表里",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onSaveLlm = { pid, b, m, k ->
                                    MossEngine.saveLlmConfig(this@MainActivity, pid, b, m, k)
                                },
                                onSaveAudio = { ns, g ->
                                    MossEngine.saveAudioOptions(this@MainActivity, ns, g)
                                },
                                onSave = { k ->
                                    if (k.isBlank()) MossEngine.clearKey(this@MainActivity)
                                    else MossEngine.saveKey(this@MainActivity, k)
                                    showSettings = false
                                },
                                onClear = { MossEngine.clearKey(this@MainActivity) },
                                onBack = { showSettings = false }
                            )
                        }

                        openId != null -> {
                            val note = state.notes.firstOrNull { it.id == openId }
                            NoteDetailScreen(
                                note = note,
                                onBack = { MossEngine.openNote(null) },
                                onShare = { note?.let { shareNote(it) } },
                                onExport = { note?.let { exportNote(it) } },
                                onDelete = {
                                    note?.let {
                                        NoteStore.delete(it.id)
                                        MossEngine.refreshNotes(this@MainActivity)
                                        MossEngine.openNote(null)
                                    }
                                },
                                onSaveEdit = { md ->
                                    note?.let {
                                        val (t, b) = NoteStore.splitMarkdown(md, it.title)
                                        if (t.isNotBlank() || b.isNotBlank()) {
                                            NoteStore.update(it.id, b, t)
                                            MossEngine.refreshNotes(this@MainActivity)
                                            Toast.makeText(this@MainActivity, "已保存", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                callRunning = state.running,
                                onContinue = {
                                    // 回主界面再看球 + 漂浮标题；继续聊的入口从这里带走笔记 id
                                    note?.let { n ->
                                        MossEngine.openNote(null)
                                        askAndStart(n.id)
                                    }
                                }
                            )
                        }

                        else -> HomeScreen(
                            state = state,
                            onToggle = {
                                // 留一行日志：以后调「挂断为什么弹窗」这类问题，adb logcat 直接能看
                                android.util.Log.i(
                                    "MossUI",
                                    "onToggle running=${state.running} busy=${state.noteBusy} " +
                                        "hasNote=${MossEngine.hasNoteThisCall()}"
                                )
                                if (!state.running) askAndStart()
                                // 已经在整理笔记了 → 直接挂断。整理读的是已落盘的录音，
                                // 挂断不影响出笔记，再弹一次「这通还没存笔记」纯属打断。
                                else if (state.noteBusy) stopCall()
                                // 继续聊挂断：原文从头到尾没动过，没有「还没存」的损失，不弹窗直接挂
                                else if (state.continueTitle != null) stopCall()
                                else if (!MossEngine.hasNoteThisCall()) askSaveNote = true
                                else stopCall()
                            },
                            onSettings = { showSettings = true },
                            onOpenNote = { MossEngine.openNote(it) },
                            onShareNote = { shareNote(it) },
                            onDeleteNote = { n ->
                                NoteStore.delete(n.id)
                                MossEngine.refreshNotes(this@MainActivity)
                                Toast.makeText(this@MainActivity, "已删除：${n.title}", Toast.LENGTH_SHORT).show()
                            },
                            onGenerateNote = { MossEngine.generateNote(this@MainActivity) },
                            onRegenerateNote = {
                                Toast.makeText(this@MainActivity, "重新整理这一通（不看是否有新内容）", Toast.LENGTH_SHORT).show()
                                MossEngine.generateNote(this@MainActivity, true)
                            }
                        )
                    }

                    if (askSaveNote) {
                        AlertDialog(
                            onDismissRequest = { askSaveNote = false },
                            title = { Text("这通还没存笔记") },
                            text = { Text("要先把刚才聊的整理成一篇笔记吗？整理大概几秒，完成后自动挂断。") },
                            confirmButton = {
                                TextButton(onClick = {
                                    askSaveNote = false
                                    MossEngine.generateNote(this@MainActivity, false) { _ ->
                                        runOnUiThread { stopCall() }
                                    }
                                }) { Text("先整理再挂断", color = ACCENT) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    askSaveNote = false
                                    stopCall()
                                }) { Text("直接挂断", color = MUTED) }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun askAndStart(continueId: String? = null) {
        pendingContinueId = continueId
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS

        if (need.isEmpty()) startCall() else perms.launch(need.toTypedArray())
    }

    private fun startCall() {
        val i = Intent(this, SessionService::class.java).setAction(SessionService.ACTION_START)
        pendingContinueId?.let { i.putExtra(SessionService.EXTRA_CONTINUE_NOTE_ID, it) }
        pendingContinueId = null
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopCall() {
        startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
    }

    private fun shareNote(note: NoteStore.Note) {
        val f = NoteStore.fileFor(note.id)
        if (f == null) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            this, "net.mosslive.notebook.fileprovider", f
        )
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TEXT, note.markdown)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "分享笔记"))
    }

    private fun exportNote(note: NoteStore.Note) {
        val src = NoteStore.fileFor(note.id)
        if (src == null) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "${note.id}.md")
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/MOSS笔记"
                    )
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Toast.makeText(this, "导出失败：无法创建文件", Toast.LENGTH_SHORT).show()
                    return
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                Toast.makeText(this, "已导出到 下载/MOSS笔记/${note.id}.md", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "系统版本过低，请用「分享」保存", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// ---------------- 主界面：上半球 / 下半笔记 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
    state: MossEngine.UiState,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onOpenNote: (String) -> Unit,
    onShareNote: (NoteStore.Note) -> Unit,
    onDeleteNote: (NoteStore.Note) -> Unit,
    onGenerateNote: () -> Unit,
    onRegenerateNote: () -> Unit,
) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // 左滑删除要二次确认：先记下待删笔记，确认弹窗点「删除」才真正执行
    var pendingDelete by remember { mutableStateOf<NoteStore.Note?>(null) }
    val shown = remember(state.notes, query) {
        if (query.isBlank()) state.notes
        else state.notes.filter {
            it.title.contains(query, true) || it.body.contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MOSS 笔记", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = INK)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                GlyphButton(
                    "搜索笔记",
                    onClick = {
                        showSearch = !showSearch
                        if (!showSearch) query = ""
                    }
                ) { SearchGlyph() }
                GlyphButton("设置", onClick = onSettings) { SettingsGlyph() }
            }
        }

        if (showSearch) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(showSearch) { focus.requestFocus() }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜标题或正文…", fontSize = 13.sp, color = MUTED) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = INK),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)
                    .focusRequester(focus),
                shape = RoundedCornerShape(12.dp)
            )
        }

        // 上半：声音球
        Box(Modifier.fillMaxWidth().weight(0.52f), contentAlignment = Alignment.Center) {
            VoiceOrb(
                level = state.level,
                fromUser = state.levelFromUser,
                active = state.running,
                modifier = Modifier.fillMaxSize()
            )
            Column(
                Modifier.fillMaxSize().padding(bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                // 继续聊：目标笔记标题漂浮在球下方，高亮胶囊 + 呼吸微光；挂断即随状态清空消失
                val cTitle = state.continueTitle
                if (cTitle != null) {
                    ContinuePill(cTitle)
                    Spacer(Modifier.height(6.dp))
                }
                if (state.running) {
                    Text(state.status, fontSize = 13.sp, color = MUTED)
                }
                if (state.debug && state.turns.isNotEmpty()) {
                    Text(
                        "最近：${state.turns.last().text}",
                        fontSize = 10.sp, color = MUTED.copy(alpha = 0.85f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 28.dp, end = 28.dp)
                    )
                }
            }
        }

        // 下半：笔记
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("笔记", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = INK)
            Spacer(Modifier.width(6.dp))
            Text(
                if (query.isBlank()) "${state.notes.size}" else "${shown.size}/${state.notes.size}",
                fontSize = 12.sp, color = MUTED
            )
            Spacer(Modifier.weight(1f))
            if (state.noteBusy) {
                Text(
                    if (state.running) "整理中…" else "整理中…（已挂断）",
                    fontSize = 11.sp, color = ACCENT
                )
            } else {
                Text(
                    "＋ 生成笔记",
                    fontSize = 12.sp, color = ACCENT,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = onGenerateNote,
                            onLongClick = onRegenerateNote
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            if (state.debug) Text("调试开", fontSize = 10.sp, color = MUTED)
        }

        Box(Modifier.fillMaxWidth().weight(0.42f)) {
            if (shown.isEmpty()) {
                Text(
                    when {
                        state.notes.isEmpty() ->
                            "还没有笔记。\n通话里说「帮我把刚才聊的生成笔记」，它就会出现在这里。"
                        else -> "没有匹配「$query」的笔记"
                    },
                    fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 18.dp)
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.id }) { n ->
                        SwipeableNoteCard(
                            n,
                            onOpen = { onOpenNote(n.id) },
                            onShare = { onShareNote(n) },
                            onDelete = { pendingDelete = n }
                        )
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }
            }

            if (state.toast != null) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .background(ACCENT.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(state.toast, fontSize = 12.sp, color = Color.White)
                }
            }
        }

        if (!state.error.isNullOrBlank()) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                    .background(WARN_BG, RoundedCornerShape(10.dp))
                    .padding(9.dp)
            ) {
                SelectionContainer {
                    Text(state.error, fontSize = 11.sp, color = WARN_INK)
                }
            }
        }

        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.running) Color(0xFFB3402F) else ACCENT
                )
            ) {
                Text(if (state.running) "挂断" else "开始通话", fontSize = 16.sp)
            }
        }
    }

    // 左滑删除的确认窗：确认后才真正删（NoteStore.delete + 刷新 + Toast 仍走 onDeleteNote）
    pendingDelete?.let { n ->
        val nm = n.title.trim()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这篇笔记？") },
            text = {
                Text(if (nm.isEmpty()) "这篇笔记删除后无法恢复。" else "《$nm》删除后无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteNote(n)
                }) { Text("删除", color = Color(0xFFB3402F)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = MUTED) }
            }
        )
    }
}

/** 「继续聊」的漂浮标题：绿色高亮胶囊，呼吸微光；纯展示不可点。 */
@Composable
private fun ContinuePill(title: String) {
    val glow = rememberInfiniteTransition(label = "pillGlow").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    Box(
        Modifier
            .background(ACCENT.copy(alpha = 0.10f + 0.08f * glow.value), RoundedCornerShape(16.dp))
            .border(1.2.dp, ACCENT.copy(alpha = 0.30f + 0.45f * glow.value), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics { contentDescription = "继续聊中的笔记：$title" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp)
                    .background(Color(0xFF46B582).copy(alpha = 0.6f + 0.4f * glow.value), CircleShape)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "继续聊 · $title",
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ACCENT,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 250.dp)
            )
        }
    }
}

@Composable
private fun VoiceOrb(level: Float, fromUser: Boolean, active: Boolean, modifier: Modifier) {
    val target by rememberUpdatedState(level)
    val activeNow by rememberUpdatedState(active)
    val userNow by rememberUpdatedState(fromUser)
    var targetSmooth by remember { mutableStateOf(0f) }
    var smooth by remember { mutableStateOf(0f) }
    var userMix by remember { mutableStateOf(0f) }
    var phase by remember { mutableStateOf(0f) }
    var prev by remember { mutableStateOf(0L) }

    // 时间常数驱动（按帧间隔算系数），不再用固定步长 → 不会随采样台阶闪
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { n ->
                val dt = if (prev == 0L) 0.016f else ((n - prev) / 1_000_000_000f).coerceIn(0.004f, 0.08f)
                prev = n
                val t = if (activeNow) target else 0f

                // 第一层：把 125ms 采样的台阶抹平
                val kT = 1f - exp(-dt / 0.12f)
                targetSmooth += (t - targetSmooth) * kT

                // 第二层：快起慢落（起 60ms / 落 500ms），眼睛看到的是缓的
                val tau = if (targetSmooth > smooth) 0.06f else 0.50f
                val kE = 1f - exp(-dt / tau)
                smooth += (targetSmooth - smooth) * kE

                // 说话方颜色 350ms 过渡，避免来回跳色
                val want = if (userNow) 1f else 0f
                userMix += (want - userMix) * (1f - exp(-dt / 0.35f))

                phase += dt * 1.6f
            }
        }
    }

    val deep = Color(0xFF2F6D4F)
    val bright = Color(0xFF46B582)
    val idle = Color(0xFF8FB9A2)
    val speaking = lerp(deep, bright, userMix)
    val core = if (active) speaking else idle

    Canvas(modifier) {
        val c = center
        val base = size.minDimension * 0.17f
        val breath = if (active) 0f else (sin(phase).toFloat() * 0.04f)
        val r = base * (1f + 0.34f * smooth + breath)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(core.copy(alpha = 0.16f + 0.10f * smooth), Color.Transparent),
                center = c,
                radius = r * 2.5f
            ),
            radius = r * 2.5f,
            center = c
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    core.copy(alpha = 0.95f),
                    core.copy(alpha = 0.62f),
                    core.copy(alpha = 0.20f)
                ),
                center = Offset(c.x - r * 0.22f, c.y - r * 0.28f),
                radius = r * 1.7f
            ),
            radius = r,
            center = c
        )
        if (active) {
            drawCircle(
                color = core.copy(alpha = 0.10f + 0.20f * smooth),
                radius = r * (1.06f + 0.40f * smooth),
                center = c,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

/** 与 UI 同语言的放大镜：2dp 圆头描边，跟正文/按钮同一套线宽。 */
@Composable
private fun SearchGlyph(color: Color = ACCENT) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val r = size.minDimension * 0.31f
        val c = Offset(size.width * 0.43f, size.height * 0.41f)
        drawCircle(color, radius = r, center = c, style = Stroke(width = stroke))
        val k = r * 0.70f
        drawLine(
            color,
            start = Offset(c.x + k, c.y + k),
            end = Offset(size.width * 0.90f, size.height * 0.90f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 自绘齿轮：8 齿外环 + 中心孔，线宽/圆头与 SearchGlyph 同一套。
 * 20dp 下每颗齿只有 ~3px 宽，齿数再多（12 齿）齿间空隙就糊成实心圆饼，故取 8 齿；
 * 齿深 0.18r 是 25px 栅格下仍能看出起伏的下限。
 */
@Composable
private fun SettingsGlyph(color: Color = ACCENT) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val s = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ro = s * 0.42f           // 齿顶半径
        val ri = ro * 0.82f          // 齿根半径（齿深 = 0.18 * ro）
        val rim = s * 0.15f          // 中心孔半径
        val teeth = 8
        val step = 360f / teeth      // 45 度
        val tipHalf = step * 0.20f   // 齿顶半角 9 度
        val rootHalf = step * 0.30f  // 齿根半角 13.5 度
        val rootBox = Rect(cx - ri, cy - ri, cx + ri, cy + ri)
        val tipBox = Rect(cx - ro, cy - ro, cx + ro, cy + ro)
        val deg = PI.toFloat() / 180f

        fun at(angle: Float, r: Float) = Offset(cx + r * cos(angle * deg), cy + r * sin(angle * deg))

        // 齿形环：齿根弧 -> 左斜面 -> 齿顶弧 -> 右斜面，逐齿拼一圈后 close()。
        // 斜面用直线，转角靠 StrokeJoin.Round 收口，所以 20dp 下不会出现尖角毛刺。
        val gear = Path()
        val start = at(-90f - step + rootHalf, ri)
        gear.moveTo(start.x, start.y)
        for (i in 0 until teeth) {
            val th = -90f + i * step
            gear.arcTo(rootBox, th - step + rootHalf, step - 2 * rootHalf, false)
            val tipL = at(th - tipHalf, ro)
            gear.lineTo(tipL.x, tipL.y)
            gear.arcTo(tipBox, th - tipHalf, 2 * tipHalf, false)
            val rootR = at(th + rootHalf, ri)
            gear.lineTo(rootR.x, rootR.y)
        }
        gear.close()

        drawPath(
            gear,
            color,
            style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )
        drawCircle(color, radius = rim, center = Offset(cx, cy), style = Stroke(width = stroke))
    }
}

/** 与放大镜同一套语言的自绘「复制」图标：两张错位的圆角纸，1.35dp 圆头描边。 */
@Composable
private fun CopyGlyph(color: Color = ACCENT) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val sheetW = size.minDimension * 0.50f
        val sheetH = size.minDimension * 0.56f
        val radius = 2.5.dp.toPx()
        val backX = size.width * 0.12f
        val backY = size.height * 0.12f
        val frontX = size.width * 0.34f
        val frontY = size.height * 0.32f

        // 后面那张只画前张遮不住的部分（左条 + 上条），否则两张纸的线会在内部互穿，
        // 20dp 下糊成一片。两段裁剪互不重叠，避免同一像素画两遍变成半透明加深。
        clipRect(0f, 0f, frontX, size.height) {
            drawRoundRect(
                color = color.copy(alpha = 0.55f),
                topLeft = Offset(backX, backY),
                size = Size(sheetW, sheetH),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke)
            )
        }
        clipRect(frontX, 0f, size.width, frontY) {
            drawRoundRect(
                color = color.copy(alpha = 0.55f),
                topLeft = Offset(backX, backY),
                size = Size(sheetW, sheetH),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke)
            )
        }
        // 前面那张：完整描边，压在后张的开口之上
        drawRoundRect(
            color = color,
            topLeft = Offset(frontX, frontY),
            size = Size(sheetW, sheetH),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke)
        )
    }
}

/** 自绘「编辑」：斜置铅笔（笔尖 + 笔身 + 笔箍），线宽/圆头与 CopyGlyph 完全一致。 */
@Composable
private fun EditGlyph(color: Color = ACCENT) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val w = size.minDimension
        // 轴向 45°：u 指向右上，n 为法向。s 沿轴 0..1，k 为 ±1 的法向偏移。
        val ux = 0.70711f
        val uy = -0.70711f
        val nx = 0.70711f
        val ny = 0.70711f
        val tipX = w * 0.185f
        val tipY = w * 0.785f
        val len = w * 0.72f
        val halfW = w * 0.115f
        fun pt(s: Float, k: Float) = Offset(
            tipX + ux * len * s + nx * halfW * k,
            tipY + uy * len * s + ny * halfW * k
        )
        // 笔尖 → 笔肩两侧 → 笔尾两角 → 回到笔尖，一圈闭合
        val s1 = pt(0.30f, 1f)
        val s2 = pt(0.30f, -1f)
        val e1 = pt(1f, 1f)
        val e2 = pt(1f, -1f)
        val body = Path().apply {
            moveTo(tipX, tipY)
            lineTo(s1.x, s1.y)
            lineTo(e1.x, e1.y)
            lineTo(e2.x, e2.y)
            lineTo(s2.x, s2.y)
            close()
        }
        drawPath(
            body,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // 笔箍：靠近笔尾的一道横线，让轮廓读成「铅笔」而不是一根斜杠
        val f1 = pt(0.84f, 1f)
        val f2 = pt(0.84f, -1f)
        drawLine(color, f1, f2, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** 自绘「分享」：三节点（两点一线），圆环节点 + 内缩连线。 */
@Composable
private fun ShareGlyph(color: Color = ACCENT) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val w = size.minDimension
        val r = w * 0.115f
        val left = Offset(w * 0.270f, w * 0.500f)
        val topRight = Offset(w * 0.730f, w * 0.245f)
        val botRight = Offset(w * 0.730f, w * 0.755f)
        val ring = Stroke(width = stroke)
        drawCircle(color, radius = r, center = left, style = ring)
        drawCircle(color, radius = r, center = topRight, style = ring)
        drawCircle(color, radius = r, center = botRight, style = ring)
        // 连线两端各内缩一个半径，否则线会穿进圆环里，20dp 下糊成一团
        fun link(p: Offset, q: Offset) {
            val dx = q.x - p.x
            val dy = q.y - p.y
            val d = hypot(dx, dy)
            if (d <= r * 2f) return
            val kx = dx / d * r
            val ky = dy / d * r
            drawLine(
                color,
                start = Offset(p.x + kx, p.y + ky),
                end = Offset(q.x - kx, q.y - ky),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        link(left, topRight)
        link(left, botRight)
    }
}

/** 自绘「导出」：向下箭头 + 托盘（导出到本地文件）。 */
@Composable
private fun ExportGlyph(color: Color = ACCENT) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val w = size.minDimension
        // 箭头杆
        drawLine(
            color,
            start = Offset(w * 0.50f, w * 0.16f),
            end = Offset(w * 0.50f, w * 0.61f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // 箭头两翼
        drawLine(
            color,
            start = Offset(w * 0.335f, w * 0.445f),
            end = Offset(w * 0.50f, w * 0.61f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color,
            start = Offset(w * 0.665f, w * 0.445f),
            end = Offset(w * 0.50f, w * 0.61f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // 托盘：两侧竖边 + 底边
        val tray = Path().apply {
            moveTo(w * 0.20f, w * 0.645f)
            lineTo(w * 0.20f, w * 0.84f)
            lineTo(w * 0.80f, w * 0.84f)
            lineTo(w * 0.80f, w * 0.645f)
        }
        drawPath(
            tray,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** 自绘「删除」：带盖 + 提手的垃圾桶，默认红。 */
@Composable
private fun TrashGlyph(color: Color = Color(0xFFB3402F)) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        val w = size.minDimension
        // 盖子
        drawLine(
            color,
            start = Offset(w * 0.17f, w * 0.255f),
            end = Offset(w * 0.83f, w * 0.255f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // 提手
        val handle = Path().apply {
            moveTo(w * 0.375f, w * 0.255f)
            lineTo(w * 0.41f, w * 0.145f)
            lineTo(w * 0.59f, w * 0.145f)
            lineTo(w * 0.625f, w * 0.255f)
        }
        drawPath(
            handle,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // 桶身：上宽下窄，开口不封（封起来就读成矩形了）
        val body = Path().apply {
            moveTo(w * 0.265f, w * 0.335f)
            lineTo(w * 0.315f, w * 0.845f)
            lineTo(w * 0.685f, w * 0.845f)
            lineTo(w * 0.735f, w * 0.335f)
        }
        drawPath(
            body,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * 顶栏图标按钮：40dp 圆形触控区 + 居中 20dp glyph。
 * 不用 IconButton/TextButton —— 它们自带 58dp minWidth，五个并排会撑爆窄屏顶栏。
 */
@Composable
private fun GlyphButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 一键复制整篇（标题 + 正文）；空内容只提示，不往剪贴板塞空串。 */
private fun copyBody(ctx: Context, text: String) {
    if (text.isBlank()) {
        Toast.makeText(ctx, "这篇笔记是空的，没有可复制的", Toast.LENGTH_SHORT).show()
        return
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm == null) {
        Toast.makeText(ctx, "复制失败：剪贴板不可用", Toast.LENGTH_SHORT).show()
        return
    }
    cm.setPrimaryClip(ClipData.newPlainText("MOSS 笔记", text))
    Toast.makeText(ctx, "已复制整篇笔记（${text.length} 字）", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNoteCard(
    note: NoteStore.Note,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) {
                // 只弹确认，绝不放行：让 SwipeToDismissBox 自己把卡片弹回原位，
                // 否则条目会先消失、用户点「取消」后却已经少了一行。
                onDelete()
            }
            // EndToStart / StartToEnd 一律拒绝（false）；Settled 放行，供 reset() 复位
            v == SwipeToDismissBoxValue.Settled
        }
    )
    // 兜底：万一状态停在滑开位置（比如回调期间重组），主动复位到原位，可再次左滑
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) dismiss.reset()
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0xFFB3402F), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text("删除", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    ) {
        NoteCard(note, onOpen = onOpen, onShare = onShare)
    }
}

@Composable
private fun NoteCard(note: NoteStore.Note, onOpen: () -> Unit, onShare: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(CARD, RoundedCornerShape(14.dp))
            .clickable { onOpen() }
            .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 4.dp)
    ) {
        Text(
            note.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = INK, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (note.preview.isNotBlank()) {
            Text(
                note.preview, fontSize = 12.sp, color = MUTED,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp, end = 8.dp)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${timeText(note.createdAt)} · ${note.body.length} 字", fontSize = 10.sp, color = MUTED)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onShare) { Text("分享", fontSize = 12.sp, color = ACCENT) }
            TextButton(onClick = onOpen) { Text("打开", fontSize = 12.sp, color = ACCENT) }
        }
    }
}

@Composable
private fun NoteDetailScreen(
    note: NoteStore.Note?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onSaveEdit: (markdown: String) -> Unit,
    callRunning: Boolean,
    onContinue: () -> Unit,
) {
    var editing by rememberSaveable(note?.id) { mutableStateOf(false) }
    // 编辑态就是「整篇 Markdown」：第一行 `# 标题`，空行后是正文 —— 与阅读页同一份文本，
    // 与剪贴板里复制走的也是同一份，不再有独立的「标题栏」字段。
    var draft by rememberSaveable(note?.id) { mutableStateOf(note?.markdown ?: "") }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (editing) editing = false else onBack() }) {
                Text(if (editing) "取消" else "‹ 返回", fontSize = 14.sp, color = ACCENT)
            }
            Spacer(Modifier.weight(1f))
            if (editing) {
                TextButton(onClick = {
                    if (draft.isBlank()) return@TextButton
                    onSaveEdit(draft)
                    editing = false
                }) { Text("保存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ACCENT) }
            } else {
                // 非编辑态顺序：复制 → 编辑 → 分享 → 导出 → 删除。
                // 破坏性的「删除」被推到最右端，不再紧邻复制，降低误触。
                GlyphButton("复制整篇笔记", onClick = {
                    copyBody(ctx, if (editing) draft else note?.markdown ?: "")
                }) { CopyGlyph() }
                // 文字按钮改成自绘小图标：40dp 圆形触控区，视觉重量与 CopyGlyph 一致
                GlyphButton("编辑", onClick = {
                    draft = note?.markdown ?: ""
                    editing = true
                }) { EditGlyph() }
                GlyphButton("分享", onClick = onShare) { ShareGlyph() }
                GlyphButton("导出", onClick = onExport) { ExportGlyph() }
                GlyphButton("删除", onClick = { showDeleteConfirm = true }) { TrashGlyph() }
            }
            // 编辑态：复制仍在最右（复制的是草稿）
            if (editing) {
                GlyphButton("复制整篇笔记", onClick = {
                    copyBody(ctx, if (editing) draft else note?.markdown ?: "")
                }) { CopyGlyph() }
            }
        }

        if (showDeleteConfirm) {
            val nm = note?.title?.trim().orEmpty()
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("删除这篇笔记？") },
                text = {
                    Text(if (nm.isEmpty()) "这篇笔记删除后无法恢复。" else "《$nm》删除后无法恢复。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }) { Text("删除", color = Color(0xFFB3402F)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消", color = MUTED) }
                }
            )
        }

        if (note == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("笔记不存在", fontSize = 14.sp, color = MUTED)
            }
            return@Column
        }

        if (editing) {
            // imePadding：键盘弹出时把编辑区抬到键盘上方。
            // 配合 manifest 的 adjustResize（窗口先被系统压缩时 ime 内边距为 0，此修饰符是空操作），
            // 两种分发方式下都能保证输入框可见。
            Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Markdown", fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = INK),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            return@Column
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .weight(1f)
        ) {
            // 标题不再单独占一行：它就是正文第一行的 `# 标题`，跟着正文一起滚动、一起被选中、一起被复制走。
            SelectionContainer {
                MarkdownBody(note.markdown)
            }
            Spacer(Modifier.height(40.dp))
        }

        // 底部「继续聊」：与主界面通话按钮同款（绿色胶囊）。通话中不给进（一通只有一条线）。
        if (!editing) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
                Button(
                    onClick = onContinue,
                    enabled = !callRunning,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ACCENT,
                        disabledContainerColor = ACCENT.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        if (callRunning) "通话中…" else "继续聊",
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

private fun timeText(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))

/** 轻量 Markdown 渲染：标题 / 项目符号 / 引用 / 加粗 / 行内代码。足够看笔记，不引第三方库。 */
@Composable
private fun MarkdownBody(md: String) {
    Column {
        md.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))

                line.startsWith("### ") -> Text(
                    inlineMd(line.removePrefix("### ")),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = INK,
                    lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )

                line.startsWith("## ") -> Text(
                    inlineMd(line.removePrefix("## ")),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = INK,
                    lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp, bottom = 3.dp)
                )

                line.startsWith("# ") -> Text(
                    inlineMd(line.removePrefix("# ")),
                    fontSize = 19.sp, fontWeight = FontWeight.Bold, color = INK,
                    lineHeight = 26.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )

                line.startsWith("- ") || line.startsWith("* ") -> Row(Modifier.padding(bottom = 4.dp)) {
                    Text("·", fontSize = 15.sp, color = MUTED, modifier = Modifier.padding(end = 8.dp))
                    Text(inlineMd(line.drop(2)), fontSize = 15.sp, color = INK, lineHeight = 23.sp)
                }

                line.startsWith("> ") -> Row(Modifier.padding(vertical = 4.dp)) {
                    Box(
                        Modifier.width(2.dp).height(18.dp)
                            .background(MUTED.copy(alpha = 0.4f), RoundedCornerShape(1.dp))
                    )
                    Text(
                        inlineMd(line.removePrefix("> ")),
                        fontSize = 13.sp, color = MUTED, lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }

                line.startsWith("```") -> Unit

                else -> Text(
                    inlineMd(line), fontSize = 15.sp, color = INK, lineHeight = 24.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

private fun inlineMd(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end > i) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(src.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(src[i]); i++
                }
            }

            src[i] == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    pushStyle(SpanStyle(color = ACCENT, background = Color(0xFFEFF5F1)))
                    append(src.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(src[i]); i++
                }
            }

            else -> {
                append(src[i]); i++
            }
        }
    }
}

// ---------------- 设置 ----------------

@Composable
private fun SettingsScreen(
    info: MossEngine.KeyInfo,
    llm: LlmConfig,
    llmInfo: String,
    onSaveLlm: (providerId: String, baseUrl: String, model: String, key: String) -> Unit,
    nsOn: Boolean,
    gain: Float,
    debug: Boolean,
    onDebugChange: (Boolean) -> Unit,
    onSelfTest: () -> Unit,
    onRestoreGuide: () -> Unit,
    onSaveAudio: (Boolean, Float) -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var providerId by rememberSaveable { mutableStateOf(llm.providerId) }
    var base by rememberSaveable { mutableStateOf(llm.baseUrl) }
    var model by rememberSaveable { mutableStateOf(llm.model) }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var llmLine by rememberSaveable { mutableStateOf(llmInfo) }
    var ns by rememberSaveable { mutableStateOf(nsOn) }
    var g by rememberSaveable { mutableStateOf(gain) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 14.sp, color = ACCENT) }
            Spacer(Modifier.weight(1f))
            Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = INK)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(70.dp))
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Text("API Key", fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("当前来源：${info.source}", fontSize = 14.sp, color = INK)
            Text("指纹：${info.masked}", fontSize = 13.sp, color = MUTED)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.trim() },
                label = { Text("粘贴新的 MOSS API Key（sk-…）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation =
                    if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { reveal = !reveal }) {
                    Text(if (reveal) "隐藏" else "显示", fontSize = 13.sp, color = ACCENT)
                }
                Spacer(Modifier.weight(1f))
                if (info.hasCustom) {
                    TextButton(onClick = {
                        onClear()
                        draft = ""
                    }) { Text("清空自定义", fontSize = 13.sp, color = MUTED) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onSave(draft) },
                enabled = draft.length >= 16,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
            ) {
                Text("保存并使用", fontSize = 15.sp)
            }

            Spacer(Modifier.height(22.dp))
            Text("整理笔记的模型（可选）", fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("不填 key 就用本地规则整理；key 只存本机，不上传。", fontSize = 11.sp, color = MUTED)
            Spacer(Modifier.height(8.dp))

            Box {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "提供商：${LlmProviders.byId(providerId)?.label ?: providerId}",
                        fontSize = 14.sp, color = INK
                    )
                    Spacer(Modifier.weight(1f))
                    Text("▾", fontSize = 14.sp, color = MUTED)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    LlmProviders.all.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(p.label, fontSize = 14.sp, color = INK)
                                    if (p.hint.isNotBlank()) {
                                        Text(p.hint, fontSize = 11.sp, color = MUTED)
                                    }
                                }
                            },
                            onClick = {
                                menuOpen = false
                                val c = MossEngine.llmConfigFor(ctx, p.id)
                                providerId = p.id
                                base = c.baseUrl
                                model = c.model
                                keyDraft = ""
                                llmLine = MossEngine.describeLlm(c)
                            }
                        )
                    }
                }
            }
            val presetHint = LlmProviders.byId(providerId)?.hint.orEmpty()
            if (presetHint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(presetHint, fontSize = 11.sp, color = MUTED)
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it.trim() },
                label = { Text("模型名") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = base,
                onValueChange = { base = it.trim() },
                label = { Text("Base URL（OpenAI 兼容）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it.trim() },
                label = {
                    Text(
                        if (LlmProviders.needsKey(providerId)) "粘贴 API Key"
                        else "API Key（这个提供商不需要）"
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            val canSave = base.isNotBlank() && model.isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        onSaveLlm(providerId, base, model, keyDraft)
                        llmLine = MossEngine.describeLlm(MossEngine.llmConfigFor(ctx, providerId))
                        keyDraft = ""
                        Toast.makeText(ctx, "模型设置已保存", Toast.LENGTH_SHORT).show()
                    },
                    enabled = canSave
                ) {
                    Text("保存模型设置", fontSize = 13.sp, color = if (canSave) ACCENT else MUTED)
                }
                Spacer(Modifier.weight(1f))
                if (keyDraft.isNotBlank()) {
                    Text("改动未保存", fontSize = 11.sp, color = MUTED)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("当前生效：$llmLine", fontSize = 12.sp, color = MUTED)

            Spacer(Modifier.height(20.dp))
            Text("上行音频（下次通话生效）", fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("系统降噪 NS", fontSize = 14.sp, color = INK)
                Spacer(Modifier.weight(1f))
                Switch(checked = ns, onCheckedChange = { v ->
                    ns = v
                    onSaveAudio(v, g)
                })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("上行增益", fontSize = 14.sp, color = INK)
                Spacer(Modifier.weight(1f))
                listOf(1.0f, 1.5f, 2.0f).forEach { v ->
                    TextButton(onClick = {
                        g = v
                        onSaveAudio(ns, v)
                    }) {
                        Text(
                            "x$v", fontSize = 13.sp,
                            color = if (g == v) ACCENT else MUTED,
                            fontWeight = if (g == v) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("调试日志", fontSize = 14.sp, color = INK)
                Spacer(Modifier.weight(1f))
                Switch(checked = debug, onCheckedChange = onDebugChange)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("存储自测", fontSize = 14.sp, color = INK)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSelfTest) {
                    Text("写入一篇测试笔记", fontSize = 13.sp, color = ACCENT)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("使用说明", fontSize = 14.sp, color = INK)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRestoreGuide) {
                    Text("重新写入使用说明", fontSize = 13.sp, color = ACCENT)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "说明\n" +
                    "· 笔记以 Markdown 文件保存在本机 App 私有目录（卸载即清空）。\n" +
                    "· 每账号只允许 1 路并发会话。\n" +
                    "· key 只存在本机 App 私有目录，不上传、不入库。",
                fontSize = 12.sp, color = MUTED, lineHeight = 19.sp
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
