package dev.autohotspot

import android.os.PowerManager
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

internal data class PixelShift(val x: Int, val y: Int)

/** Like mijiapanel: visit the entire shuffled grid before reusing a position. */
internal class BurnInPath(private val random: Random = Random.Default) {
    companion object {
        const val INTERVAL_MS = 180_000L
        const val RADIUS_PX = 16
        const val ANIMATION_MS = 800
    }
    private val positions = ArrayList<PixelShift>()
    private var current = PixelShift(0, 0)
    fun next(): PixelShift {
        if (positions.isEmpty()) {
            for (x in -RADIUS_PX..RADIUS_PX step 4) for (y in -RADIUS_PX..RADIUS_PX step 4) positions.add(PixelShift(x, y))
            positions.shuffle(random)
            if (positions.last() == current) {
                val last = positions.lastIndex
                val swap = positions[0]; positions[0] = positions[last]; positions[last] = swap
            }
        }
        return positions.removeAt(positions.lastIndex).also { current = it }
    }
}

@Composable internal fun rememberBurnInOffset(enabled: Boolean): IntOffset {
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    var target by remember { mutableStateOf(IntOffset.Zero) }
    LaunchedEffect(enabled, owner, view) {
        target = IntOffset.Zero
        if (!enabled) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val path = BurnInPath()
            try {
                while (true) {
                    delay(BurnInPath.INTERVAL_MS)
                    val power = view.context.getSystemService(PowerManager::class.java)
                    if (view.isShown && view.hasWindowFocus() && power.isInteractive) {
                        val next = path.next()
                        target = IntOffset(next.x, next.y)
                    }
                }
            } finally { target = IntOffset.Zero }
        }
    }
    return animateIntOffsetAsState(target, tween(BurnInPath.ANIMATION_MS), label = "burn-in shift").value
}
