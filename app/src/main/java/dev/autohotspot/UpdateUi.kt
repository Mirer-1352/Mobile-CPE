package dev.autohotspot

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import java.net.URI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable internal fun UpdateHost(activity: ComponentActivity) {
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) { delay(2200); AppUpdater.initialize(activity); AppUpdater.check(false) }
    }
    LaunchedEffect(state.feedback) {
        state.feedback?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show(); AppUpdater.feedbackShown() }
    }
    if (state.dialog && state.known != null) UpdateDialog(state)
}

@Composable internal fun UpdateSetting() {
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("检查更新", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (state.redDot) Box(Modifier.size(7.dp).background(Color(0xFFB3261E), RoundedCornerShape(50))
                    .semantics { contentDescription = "有可用更新" })
            }
            val detail = when (state.stage) {
                UpdateStage.DOWNLOADING -> "自动下载中"
                UpdateStage.VERIFYING -> "正在校验"
                UpdateStage.INSTALLING -> "Root 自动安装中"
                UpdateStage.FAILED -> state.error ?: "自动更新失败"
                else -> ""
            }
            Text(BuildConfig.VERSION_NAME + if (detail.isEmpty()) "" else " · $detail", fontSize = 12.sp,
                maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) {
            if (state.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { AppUpdater.check(true) }, enabled = !state.busy) { Text("检查") }
        }
    }
}

@Composable private fun UpdateDialog(state: UpdateState) {
    val update = state.known ?: return
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val height = with(LocalDensity.current) { windowHeight.toDp() } * .84f
    Dialog(onDismissRequest = { if (!state.required && !state.busy) AppUpdater.dismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.required && !state.busy,
            dismissOnClickOutside = !state.required && !state.busy, usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).height(height), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (state.required) "需要更新" else "发现新版本", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(update.versionName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        update.published?.let { instant ->
                            val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                                .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(instant)
                            Text("发布时间：$formatted", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        UpdateMarkdown(update.notes.ifBlank { "此版本暂无更新说明。" })
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 340.dp
                    val secondary: @Composable RowScope.() -> Unit = {
                        if (!state.required) {
                            TextButton(onClick = { AppUpdater.dismiss() }, enabled = !state.busy) { Text("关闭", fontSize = 12.sp) }
                        }
                    }
                    val primary: @Composable () -> Unit = {
                        Button(onClick = { AppUpdater.download() }, enabled = !state.busy && !state.checking,
                            modifier = Modifier.width(148.dp).height(48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            if (state.busy) {
                                val progress = state.progress
                                if (state.stage == UpdateStage.DOWNLOADING && progress != null)
                                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                                else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(when (state.stage) {
                                UpdateStage.DOWNLOADING -> state.progress?.let { "${(it * 100).toInt()}%" } ?: "下载中"
                                UpdateStage.VERIFYING -> "校验中"
                                UpdateStage.INSTALLING -> "安装中"
                                UpdateStage.FAILED -> "重试更新"
                                UpdateStage.READY -> "更新完成"
                                else -> "自动更新"
                            }, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    if (compact) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        if (!state.required) Row(content = secondary)
                        primary()
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        secondary(); Spacer(Modifier.weight(1f)); primary()
                    }
                }
            }
        }
    }
}

/** Limited Markdown, rendered entirely as native text. No HTML or remote images. */
@Composable private fun UpdateMarkdown(markdown: String) {
    var code = false
    val codeLines = mutableListOf<String>()
    val blocks = mutableListOf<Pair<String, String>>()
    for (line in markdown.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (code) { blocks += "code" to codeLines.joinToString("\n"); codeLines.clear() }
            code = !code
        } else if (code) codeLines += line
        else if (line.isNotBlank()) blocks += "text" to line
    }
    if (codeLines.isNotEmpty()) blocks += "code" to codeLines.joinToString("\n")
    blocks.forEach { (type, line) ->
        if (type == "code") Text(line, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        else {
            val trimmed = line.trimStart()
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
            if (Regex("^([-*_])(?:\\s*\\1){2,}$").matches(trimmed)) HorizontalDivider()
            else {
                val text = when {
                    heading != null -> heading.groupValues[2]
                    trimmed.startsWith("> ") -> "│ " + trimmed.drop(2)
                    Regex("^[-*+]\\s").containsMatchIn(trimmed) -> "• " + trimmed.drop(2)
                    else -> line
                }
                Text(inlineMarkdown(text), fontSize = if (heading != null) (21 - heading.groupValues[1].length).sp else 14.sp,
                    fontWeight = if (heading != null) FontWeight.Bold else FontWeight.Normal,
                    color = if (trimmed.startsWith("> ")) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current)
            }
        }
    }
}

internal fun inlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val text = source.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "[图片]")
    val pattern = Regex("\\*\\*(.+?)\\*\\*|(?<!\\*)\\*([^*]+)\\*|_([^_]+)_|`([^`]+)`|\\[([^]]+)]\\(([^)]+)\\)")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        when {
            match.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
            match.groups[2] != null || match.groups[3] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(match.groups[2]?.value ?: match.groupValues[3])
            }
            match.groups[4] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[4]) }
            else -> {
                val url = match.groupValues[6]
                val safe = runCatching { URI(url).let { it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
                if (safe) withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Color(0xFF155E75))))) { append(match.groupValues[5]) }
                else append(match.groupValues[5])
            }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
