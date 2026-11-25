package com.timcordova.quartzcam.settings

import android.content.Context
import android.content.SharedPreferences
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.quartz.NavPosition
import com.timcordova.quartzcam.quartz.QuartzLook
import com.timcordova.quartzcam.quartz.WallPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("Auto"), LIGHT("Light"), DARK("Dark") }

/** App settings, saved on the phone and shared as flows the UI can watch. */
object AppSettings {
    private var prefs: SharedPreferences? = null

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    /** Starts on the Blue quartz background, so the glass bars have something to show off. */
    private val defaultLook = QuartzLook(
        background = WallPresets.all.firstOrNull { it.name == "Blue quartz" }?.config ?: QuartzLook().background,
        // Three tabs share the bar's width evenly
        navPosition = NavPosition.WIDE,
    )

    /** Background, bars and glass: the Quartz look. */
    private val _look = MutableStateFlow(defaultLook)
    val look: StateFlow<QuartzLook> = _look.asStateFlow()

    private val _host = MutableStateFlow(CamApi.DEFAULT_HOST)
    val host: StateFlow<String> = _host.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        _theme.value = runCatching { ThemeMode.valueOf(p.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
        _look.value = QuartzLook.fromJson(p.getString("look", null)) ?: defaultLook
        _host.value = p.getString("host", null)?.takeIf { it.isNotBlank() } ?: CamApi.DEFAULT_HOST
        CamApi.host = _host.value
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs?.edit()?.putString("theme", mode.name)?.apply()
    }

    fun setLook(l: QuartzLook) {
        _look.value = l
        prefs?.edit()?.putString("look", l.toJson())?.apply()
    }

    fun setHost(host: String) {
        val h = host.trim().removePrefix("http://").trimEnd('/').ifBlank { CamApi.DEFAULT_HOST }
        _host.value = h
        CamApi.host = h
        prefs?.edit()?.putString("host", h)?.apply()
    }
}
