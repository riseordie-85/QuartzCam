package com.timcordova.quartzcam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.cam.CamSetting
import com.timcordova.quartzcam.cam.Folder
import com.timcordova.quartzcam.cam.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryState(
    val library: Library = Library.EMPTY,
    val loading: Boolean = false,
    val error: String? = null,
    /** True once the card has been read at least once. */
    val loaded: Boolean = false,
)

/** The camera's settings and their current values. */
data class CamSettingsState(
    val items: List<CamSetting> = emptyList(),
    val values: Map<String, Int> = emptyMap(),
    /** Settings being changed right now. */
    val busy: Set<String> = emptySet(),
    val error: String? = null,
) {
    /** Null when unknown (not read yet, or the camera doesn't say). */
    val recording: Boolean? get() = values["rec"]?.let { it != 0 }
}

class CamViewModel : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(CamSettingsState())
    val settings: StateFlow<CamSettingsState> = _settings.asStateFlow()

    private var loadJob: Job? = null
    private var watchJob: Job? = null

    init {
        // Read the card and the camera's settings whenever the camera comes (back) into reach
        viewModelScope.launch {
            CamConnection.state
                .map { it.status == CamConnection.Status.CONNECTED }
                .distinctUntilChanged()
                .collect { connected ->
                    watchJob?.cancel()
                    if (connected) {
                        refresh()
                        loadSettings()
                        watchJob = launch { watchRecording() }
                    }
                }
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CamApi.settingItems() to CamApi.settingValues() }
            }
            result
                .onSuccess { (items, values) -> _settings.value = CamSettingsState(items, values) }
                .onFailure { _settings.value = _settings.value.copy(error = "Couldn't read the camera's settings") }
        }
    }

    /** Changes a setting on the camera, showing the new value straight away. */
    fun setSetting(name: String, value: Int) {
        val before = _settings.value.values[name]
        _settings.value = _settings.value.let { it.copy(values = it.values + (name to value), busy = it.busy + name, error = null) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { CamApi.setSetting(name, value) }
            // Read back what the camera actually has now
            val actual = withContext(Dispatchers.IO) { CamApi.settingValue(name) }
            _settings.value = _settings.value.let { s ->
                val v = actual ?: if (ok) value else before
                s.copy(
                    values = if (v != null) s.values + (name to v) else s.values - name,
                    busy = s.busy - name,
                    error = if (ok) null else "The camera didn't accept that change",
                )
            }
        }
    }

    /** The camera's record button can change recording too, so check now and then (the stock app does every 4 s). */
    private suspend fun watchRecording() {
        while (true) {
            delay(10_000)
            val rec = withContext(Dispatchers.IO) { CamApi.settingValue("rec") } ?: continue
            val s = _settings.value
            if (s.values["rec"] != rec && "rec" !in s.busy) _settings.value = s.copy(values = s.values + ("rec" to rec))
        }
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val loop = CamApi.files(Folder.LOOP)
                    val sos = CamApi.files(Folder.SOS) + runCatching { CamApi.files(Folder.EVENT) }.getOrDefault(emptyList())
                    val sd = runCatching { CamApi.sdInfo() }.getOrNull()
                    Library.build(loop, sos, sd)
                }
            }
            result
                .onSuccess { _state.value = LibraryState(library = it, loaded = true) }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = it.message ?: "Couldn't read the camera's card")
                    CamConnection.reportFailure()
                }
        }
    }
}
