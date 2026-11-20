package dev.autohotspot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


private val DisplayBackground = Color(0xFF102A33)
private val DisplayMuted = Color(0xFFB6CBD0)
private val DisplayRule = Color(0xFF36525B)
private val DisplayConnected = Color(0xFF64DC94)

@Composable internal fun RouterDisplay(state: RouterState, options: Options, openSettings: () -> Unit,
                                     changeSharing: (Sharing, Boolean) -> Unit, openOriginal: () -> Unit) {
    val burnInOffset = rememberBurnInOffset(options.burnInProtection)
    val shiftInset = with(LocalDensity.current) { BurnInPath.RADIUS_PX.toDp() }
    Surface(Modifier.fillMaxSize().combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = {}, onLongClick = openSettings, onLongClickLabel = "打开设备设置"
    ), color = DisplayBackground, contentColor = Color.White) {
        BoxWithConstraints(Modifier.fillMaxSize().displayCutoutPadding().padding(horizontal = 24.dp, vertical = 20.dp).padding(shiftInset)) {
            val landscape = maxWidth > maxHeight
            val compact = landscape && maxHeight < 300.dp * LocalDensity.current.fontScale
            val sharing = if (state.fresh) Sharing.entries.filter { it in state.active }.joinToString(" · ") {
                when (it) { Sharing.WIFI -> "Wi-Fi"; Sharing.USB -> "USB"; Sharing.ETHERNET -> "以太网" }
            }.ifEmpty { "共享未开启" } else if (!options.enabled) "守护已暂停" else "正在连接"
            Column(Modifier.fillMaxSize().offset { burnInOffset }) {
                Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DisplayClock()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.operator, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(state.generation, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        SignalBars(state.signal)
                        if (landscape) {
                            Text(sharing, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.fresh && Sharing.WIFI in state.active) Text(state.clients?.let { "$it 台设备" } ?: "设备数 —", fontSize = 17.sp)
                        }
                    }
                    if (!landscape) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(sharing, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (state.fresh && Sharing.WIFI in state.active) Text(state.clients?.let { "$it 台设备" } ?: "设备数 —", fontSize = 17.sp)
                    }
                    if (!compact) Text(buildList {
                        add(state.dbm?.let { "$it dBm" } ?: "信号 ${state.signal?.let { "$it/4" } ?: "—"}")
                        add(when { !state.running -> "守护已暂停"; !state.root -> "Root 未连接"; else -> "守护运行中" })
                    }.joinToString("  ·  "), fontSize = 12.sp, color = DisplayMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = DisplayRule)
                if (landscape) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Rate("↓ 下行", state.down, Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(Modifier.padding(horizontal = 24.dp).fillMaxHeight(), color = DisplayRule)
                        Rate("↑ 上行", state.up, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Rate("↓ 下行", state.down, Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = DisplayRule)
                    Rate("↑ 上行", state.up, Modifier.weight(1f).fillMaxWidth())
                }
                HorizontalDivider(color = DisplayRule)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    Sharing.entries.forEach { mode ->
                        val active = state.fresh && mode in state.active
                        val highlighted = active && (mode != Sharing.WIFI || (state.clients ?: 0) > 0)
                        val iconTint = if (highlighted) DisplayConnected else Color.White
                        val iconStatus = when {
                            !state.fresh -> "状态未知"
                            !active -> "未开启"
                            mode == Sharing.WIFI -> state.clients?.let { "$it 台设备" } ?: "设备数未知"
                            else -> "已开启"
                        }
                        val toggle: @Composable () -> Unit = {
                            Switch(mode in options.desired, { changeSharing(mode, it) },
                                modifier = Modifier.semantics { contentDescription = mode.label },
                                colors = SwitchDefaults.colors(checkedTrackColor = Color.White, checkedThumbColor = DisplayBackground,
                                    uncheckedTrackColor = DisplayBackground, uncheckedBorderColor = Color.White, uncheckedThumbColor = Color.White))
                        }
                        if (landscape) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                            SharingIcon(mode, iconTint, iconStatus)
                            toggle()
                        } else Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SharingIcon(mode, iconTint, iconStatus)
                            toggle()
                        }
                    }
                }
                OriginalHomeGesture(openOriginal)
            }
        }
    }
}
@Composable private fun DisplayClock() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1000L - System.currentTimeMillis() % 1000L)
            }
        }
    }
    val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy/MM/dd EEEE", Locale.SIMPLIFIED_CHINESE) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        val stacked = maxWidth < 280.dp * LocalDensity.current.fontScale
        val date: @Composable (Modifier) -> Unit = { modifier ->
            Text(local.format(dateFormat), fontSize = 14.sp, lineHeight = 18.sp, color = DisplayMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = modifier)
        }
        val time: @Composable () -> Unit = {
            Text(local.format(timeFormat), fontSize = 20.sp, lineHeight = 24.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
        if (stacked) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            date(Modifier.fillMaxWidth()); time()
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            date(Modifier.weight(1f)); time()
        }
    }
}
@Composable private fun OriginalHomeGesture(openOriginal: () -> Unit) {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Bottom))
        .height(36.dp).semantics { contentDescription = "上划打开原桌面" }
        .pointerInput(openOriginal, threshold) {
            var upward = 0f
            detectVerticalDragGestures(onDragStart = { upward = 0f }, onDragEnd = {
                if (upward >= threshold) openOriginal()
            }, onDragCancel = { upward = 0f }) { change, amount ->
                upward -= amount
                change.consume()
            }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp, 10.dp)) {
            drawLine(DisplayMuted, Offset(0f, size.height), Offset(size.width / 2, 0f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(DisplayMuted, Offset(size.width / 2, 0f), Offset(size.width, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable private fun SharingIcon(mode: Sharing, tint: Color, status: String) {
    Canvas(Modifier.size(28.dp).semantics { contentDescription = "${mode.label}状态"; stateDescription = status }) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = 1.8f
            when (mode) {
                Sharing.WIFI -> {
                    drawArc(tint, 225f, 90f, false, Offset(1f, 3f), Size(22f, 22f), style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(tint, 225f, 90f, false, Offset(5f, 9f), Size(14f, 14f), style = Stroke(stroke, cap = StrokeCap.Round))
                    drawCircle(tint, 1.6f, Offset(12f, 20f))
                }
                Sharing.USB -> {
                    drawLine(tint, Offset(12f, 21f), Offset(12f, 3f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(12f, 14f), Offset(6f, 9f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(12f, 17f), Offset(18f, 12f), stroke, StrokeCap.Round)
                    drawCircle(tint, 2f, Offset(6f, 8f))
                    drawRect(tint, Offset(16f, 8f), Size(4f, 4f))
                    drawPath(Path().apply { moveTo(12f, 1f); lineTo(8.5f, 5.5f); lineTo(15.5f, 5.5f); close() }, tint)
                    drawCircle(tint, 2f, Offset(12f, 21f))
                }
                Sharing.ETHERNET -> {
                    drawRect(tint, Offset(3f, 4f), Size(18f, 14f), style = Stroke(stroke))
                    for (x in listOf(7f, 10f, 13f, 16f)) drawLine(tint, Offset(x, 4f), Offset(x, 9f), stroke)
                    drawLine(tint, Offset(8f, 21f), Offset(16f, 21f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(12f, 18f), Offset(12f, 21f), stroke)
                }
            }
        }
    }
}
@Composable private fun SignalBars(level: Int?) {
    Canvas(Modifier.size(44.dp, 28.dp)) {
        for (index in 0..3) {
            val width = size.width / 7
            val height = size.height * (index + 1) / 4
            drawRect(if (level != null && index < level) Color.White else Color.White.copy(alpha = .3f),
                Offset(index * size.width / 4, size.height - height), Size(width, height))
        }
    }
}
// M/s is decimal megabytes per second; keeping the unit fixed avoids a glance-time conversion.
private fun speed(value: Double?): Pair<String, String> =
    (value?.let { String.format(Locale.ROOT, "%.2f", it / 1_000_000) } ?: "—") to "M/s"
@Composable private fun Rate(label: String, value: Double?, modifier: Modifier = Modifier) {
    val rate = speed(value)
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val numberSize = minOf(124f, maxWidth.value / (maxOf(rate.first.length, 6) * .62f * scale), maxHeight.value * .5f / scale).coerceAtLeast(6f)
        val labelSize = minOf(20f, maxHeight.value * .18f / scale).coerceAtLeast(6f)
        val gap = minOf(12f, maxHeight.value * .08f).dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = DisplayMuted, fontSize = labelSize.sp, lineHeight = (labelSize * 1.15f).sp, modifier = Modifier.weight(1f))
                Text(rate.second, color = DisplayMuted, fontSize = minOf(18f, labelSize).sp, lineHeight = (labelSize * 1.15f).sp)
            }
            Spacer(Modifier.height(gap))
            Text(rate.first, modifier = Modifier.fillMaxWidth(), fontSize = numberSize.sp, lineHeight = (numberSize * 1.1f).sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.End)
        }
    }
}
