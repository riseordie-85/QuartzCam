package com.timcordova.quartzcam.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import com.timcordova.quartzcam.quartz.GradientStyle
import com.timcordova.quartzcam.quartz.Ink
import com.timcordova.quartzcam.quartz.LocalWall
import com.timcordova.quartzcam.quartz.WallCache
import com.timcordova.quartzcam.quartz.WallConfig
import com.timcordova.quartzcam.quartz.WallImage
import com.timcordova.quartzcam.quartz.WallKind
import com.timcordova.quartzcam.settings.AppSettings
import com.timcordova.quartzcam.settings.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Quartz apps take their colors from the phone's wallpaper (Android 12+, so always here). */
private fun camColorScheme(context: Context, dark: Boolean): ColorScheme =
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

/** App theme: follows the Light / Dark / Auto choice in Settings, with the phone's wallpaper colors. */
@Composable
fun CamTheme(content: @Composable () -> Unit) {
    val mode by AppSettings.theme.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SystemBarIcons(dark)
    MaterialTheme(colorScheme = camColorScheme(LocalContext.current, dark), content = content)
}

/** Light or dark status/navigation bar icons, to stay visible on the screen behind them. */
@Composable
fun SystemBarIcons(darkBackground: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !darkBackground
        c.isAppearanceLightNavigationBars = !darkBackground
    }
}

/** True inside [MainBackground] while a Quartz background is drawn (screens then draw no background of their own). */
val LocalGradientActive = staticCompositionLocalOf { false }

/** A first guess at how bright a background looks, before it's rendered. */
private fun estimateBrightness(c: WallConfig): Float {
    val used = when {
        c.kind == WallKind.SOLID -> listOf(c.solid)
        c.gradient == GradientStyle.CORNERS -> (0 until 5).map { c.color(it) }
        c.gradient == GradientStyle.RADIAL -> listOf(c.color(0), c.color(2))
        c.linearMiddle -> listOf(c.color(0), c.color(1), c.color(2))
        else -> listOf(c.color(0), c.color(2))
    }
    val avg = used.map { kotlin.math.sqrt(android.graphics.Color.luminance(it)) }.average().toFloat()
    return avg * (1f - c.dim.coerceIn(0f, 0.8f))
}

/**
 * Background for the main screens: the Quartz background (gradient, 5-point gradient or color, with an optional
 * crystal pattern). Text switches to light or dark so it stays readable on it. Same as Quartz Gallery's.
 */
@Composable
fun MainBackground(content: @Composable () -> Unit) {
    val look by AppSettings.look.collectAsState()
    val bg = look.background
    if (!bg.drawn) {
        SystemBarIcons(MaterialTheme.colorScheme.background.luminance() < 0.5f)
        CompositionLocalProvider(LocalWall provides null, content = content)
        return
    }
    // The window's size to start with (it fills the window), so a background already drawn shows on the first frame
    val root = LocalView.current.rootView
    var size by remember { mutableStateOf(IntSize(root.width, root.height)) }
    val wall by produceState(WallCache.cached(bg, size.width, size.height), bg, size) {
        if (size.width <= 0 || size.height <= 0) return@produceState
        WallCache.cached(bg, size.width, size.height)?.let {
            value = it
            return@produceState
        }
        // A quick small one first, then the sharp one
        val work = WallCache.dispatcher
        value = withContext(work) { WallCache.build(bg, size.width, size.height, 0.3f) }
        delay(120)
        value = withContext(work) { WallCache.build(bg, size.width, size.height, 1f) }
    }
    val current = wall?.takeIf { it.w == size.width && it.h == size.height }
    val brightness = current?.takeIf { it.config == bg }?.brightness ?: estimateBrightness(bg)
    val dark = Ink.prefersLight(brightness)
    SystemBarIcons(dark)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .drawBehind {
                val img = current?.full
                if (img != null) {
                    drawImage(img, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()), filterQuality = FilterQuality.Low)
                } else {
                    drawRect(Color(bg.color(0)))
                }
            },
    ) {
        MaterialTheme(colorScheme = camColorScheme(LocalContext.current, dark)) {
            CompositionLocalProvider(
                LocalGradientActive provides true,
                LocalWall provides current?.copyBrightness(brightness),
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}

private fun WallImage.copyBrightness(b: Float) =
    if (b == brightness) this else WallImage(config, w, h, full, blurred, b)

/** Scaffold background for main screens: see-through when a Quartz background is drawn. */
@Composable
fun mainContainerColor(): Color =
    if (LocalGradientActive.current) Color.Transparent else MaterialTheme.colorScheme.background

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
