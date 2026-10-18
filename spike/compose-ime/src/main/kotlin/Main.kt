// 使い捨ての試作: Compose 標準の入力欄（BasicTextField）で、DearNotes の書き方に日本語入力が耐えるかを見る。
// 点検表は本体の docs/plan/ime-checklist.md。イベントは ~/compose-ime-log.txt に追記する。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import java.time.LocalTime

private val logFile = File(System.getProperty("user.home"), "compose-ime-log.txt")

private fun log(msg: String) {
    val line = "${LocalTime.now()} $msg"
    println(line)
    logFile.appendText(line + "\n")
}

private val COMMANDS = listOf(
    "見出し1" to listOf("h1", "heading", "みだし"),
    "見出し2" to listOf("h2", "heading", "みだし"),
    "見出し3" to listOf("h3", "heading", "みだし"),
    "箇条書き" to listOf("bullet", "list"),
    "番号付き" to listOf("number", "list"),
    "ToDo" to listOf("todo", "check"),
    "引用" to listOf("quote"),
)

private var nextId = 0

private class Block(text: String, bold: TextRange? = null) {
    val id = nextId++
    var value by mutableStateOf(TextFieldValue(text))
    var heading by mutableStateOf(false)
    var bold by mutableStateOf(bold)
    val focus = FocusRequester()
}

/** 「/語句」か「／語句」だけなら語句。空白や改行を含めば null。 */
private fun slashQuery(text: String): String? {
    if (text.isEmpty() || (text[0] != '/' && text[0] != '／')) return null
    val q = text.substring(1)
    return if (q.any { it.isWhitespace() }) null else q
}

private fun filtered(q: String) = COMMANDS
    .filter { (label, keys) -> q.isEmpty() || label.contains(q) || keys.any { it.startsWith(q.lowercase()) } }
    .map { it.first }

/** 太字の範囲だけ太くして見せる。文字の位置は変えない。 */
private class BoldTransformation(private val range: TextRange?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val r = range ?: return TransformedText(text, OffsetMapping.Identity)
        val start = r.start.coerceIn(0, text.length)
        val end = r.end.coerceIn(start, text.length)
        val b = AnnotatedString.Builder(text)
        if (start < end) b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

fun main() = application {
    val runtime = "${System.getProperty("java.vm.vendor")} ${System.getProperty("java.runtime.version")}"
    log("==== 起動 ${System.getProperty("os.name")} ${System.getProperty("os.version")} / $runtime")
    Window(onCloseRequest = ::exitApplication, title = "Compose 日本語入力の試作 — $runtime") {
        Editor()
    }
}

@Composable
private fun Editor() {
    val blocks = remember {
        mutableStateListOf(
            Block("あいうえお", bold = TextRange(1, 3)),
            Block(""),
            Block(""),
        )
    }
    var focused by remember { mutableIntStateOf(0) }
    var menuIndex by remember { mutableIntStateOf(0) }
    // 確定の直後に届く Enter を捨てるか（今の ImeGuard と同じ考え方）。切り替えて比べる
    var guardEnter by remember { mutableStateOf(false) }
    var lastCommitAt by remember { mutableStateOf(0L) }
    var pendingFocus by remember { mutableStateOf<Int?>(null) }

    fun focusAt(i: Int, caret: Int?) {
        val b = blocks[i]
        val at = caret ?: b.value.text.length
        b.value = b.value.copy(selection = TextRange(at))
        pendingFocus = b.id
    }

    LaunchedEffect(pendingFocus) {
        val id = pendingFocus ?: return@LaunchedEffect
        blocks.firstOrNull { it.id == id }?.focus?.requestFocus()
        pendingFocus = null
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = guardEnter, onCheckedChange = { guardEnter = it; log("guardEnter=$it") })
            Text("確定の直後に届く Enter を捨てる（ImeGuard 方式）")
        }
        Text("記録: ${logFile.absolutePath}", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.padding(4.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            blocks.forEachIndexed { index, block ->
                key(block.id) {
                    val composing = block.value.composition != null
                    val query = slashQuery(block.value.text)
                    val items = query?.let(::filtered).orEmpty()
                    BasicTextField(
                        value = block.value,
                        onValueChange = { v ->
                            val old = block.value
                            log(
                                "change#${block.id} text='${v.text}' sel=${v.selection} comp=${v.composition}" +
                                    " (前: comp=${old.composition})",
                            )
                            if (old.composition != null && v.composition == null) lastCommitAt = System.nanoTime()
                            var next = v
                            // 行頭の「# 」「＃　」で見出しにする（記号は消す）
                            if (!block.heading && v.composition == null) {
                                val t = v.text
                                if (t.startsWith("# ") || t.startsWith("＃　") || t.startsWith("＃ ")) {
                                    block.heading = true
                                    next = TextFieldValue(t.substring(2), TextRange((v.selection.start - 2).coerceAtLeast(0)))
                                    log("見出しに変えた#${block.id}")
                                }
                            }
                            block.value = next
                            menuIndex = 0
                        },
                        textStyle = TextStyle(fontSize = if (block.heading) 26.sp else 16.sp),
                        visualTransformation = BoldTransformation(block.bold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .border(1.dp, if (focused == index) Color(0xFF6495ED) else Color(0xFFDDDDDD))
                            .padding(6.dp)
                            .focusRequester(block.focus)
                            .onFocusChanged { if (it.isFocused) focused = index }
                            .onPreviewKeyEvent { e ->
                                val awt = e.awtEventOrNull
                                log(
                                    "key#${block.id} ${e.type} ${e.key} comp=${block.value.composition}" +
                                        " awt=${awt?.paramString()}",
                                )
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                if (block.value.composition != null) return@onPreviewKeyEvent false
                                val i = blocks.indexOf(block)
                                when (e.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        val sinceCommit = (System.nanoTime() - lastCommitAt) / 1_000_000
                                        if (guardEnter && sinceCommit < 50) {
                                            log("Enter を捨てた（確定から ${sinceCommit}ms）")
                                            return@onPreviewKeyEvent true
                                        }
                                        if (items.isNotEmpty()) {
                                            val chosen = items[menuIndex.coerceIn(0, items.size - 1)]
                                            log("コマンドを選んだ: $chosen")
                                            block.heading = chosen.startsWith("見出し")
                                            block.value = TextFieldValue("")
                                            return@onPreviewKeyEvent true
                                        }
                                        val t = block.value.text
                                        val at = block.value.selection.start
                                        log("行を分けた#${block.id} at=$at")
                                        block.value = TextFieldValue(t.substring(0, at), TextRange(at))
                                        blocks.add(i + 1, Block(t.substring(at)))
                                        focusAt(i + 1, 0)
                                        true
                                    }
                                    Key.Backspace -> {
                                        if (block.value.text.isEmpty() && i > 0) {
                                            log("行を消した#${block.id}")
                                            blocks.removeAt(i)
                                            focusAt(i - 1, null)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    Key.DirectionUp -> {
                                        if (items.isNotEmpty()) {
                                            menuIndex = (menuIndex - 1).coerceAtLeast(0)
                                            true
                                        } else if (i > 0) {
                                            focusAt(i - 1, null)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    Key.DirectionDown -> {
                                        if (items.isNotEmpty()) {
                                            menuIndex = (menuIndex + 1).coerceAtMost(items.size - 1)
                                            true
                                        } else if (i < blocks.size - 1) {
                                            focusAt(i + 1, null)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    Key.B -> {
                                        if (e.isMetaPressed || e.isCtrlPressed) {
                                            val s = block.value.selection
                                            if (!s.collapsed) block.bold = TextRange(s.min, s.max)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    else -> false
                                }
                            },
                    )
                    if (items.isNotEmpty()) {
                        Column(Modifier.padding(start = 12.dp).background(Color(0xFFF4F4F4)).padding(4.dp)) {
                            items.forEachIndexed { k, label ->
                                BasicText(
                                    (if (k == menuIndex) "▶ " else "　 ") + label,
                                    style = TextStyle(fontSize = 14.sp),
                                )
                            }
                        }
                    }
                    val t = block.value.text
                    if (t.contains("[[") || t.contains("「「")) {
                        Row(Modifier.padding(start = 12.dp)) {
                            Spacer(Modifier.width(4.dp))
                            Text("ページへのリンクの候補（試作なので中身はなし）", fontSize = 13.sp, color = Color(0xFF6495ED))
                        }
                    }
                    if (composing) {
                        Text("変換中", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
