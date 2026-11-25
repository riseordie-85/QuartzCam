package com.timcordova.quartzcam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.cam.CamFile
import com.timcordova.quartzcam.cam.CamSetting
import com.timcordova.quartzcam.cam.Folder
import com.timcordova.quartzcam.cam.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** The camera's Wi-Fi name and firmware. */
    val device: CamApi.DeviceInfo? = null,
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
                        restoreAfterReconnect?.let { rec ->
                            restoreAfterReconnect = null
                            withContext(Dispatchers.IO) {
                                CamApi.exitSettings()
                                if (rec) CamApi.setSetting("rec", 1)
                            }
                        }
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
                .onSuccess { (items, values) ->
                    val device = withContext(Dispatchers.IO) { CamApi.deviceInfo() }
                    _settings.value = CamSettingsState(items, values, device = device)
                }
                .onFailure { _settings.value = _settings.value.copy(error = "Couldn't read the camera's settings") }
        }
    }

    /** After a Wi-Fi change: whether to start recording again once the camera is back (null: nothing to do). */
    @Volatile
    private var restoreAfterReconnect: Boolean? = null

    /** One camera change at a time, so their steps don't interleave. */
    private val changes = Mutex()

    /**
     * Runs [block] the way the stock app changes settings: recording stopped, the camera in settings mode, then
     * settings mode closed and recording started again (if it was on). Runs on a background thread.
     */
    private suspend fun <T> inSettingsMode(block: () -> T): T = withContext(Dispatchers.IO) {
        val wasRecording = (CamApi.settingValue("rec") ?: _settings.value.values["rec"]) != 0
        if (wasRecording) CamApi.setSetting("rec", 0)
        CamApi.enterSettings()
        try {
            block()
        } finally {
            CamApi.exitSettings()
            if (wasRecording) CamApi.setSetting("rec", 1)
        }
    }

    /** Changes a setting on the camera, showing the new value straight away. */
    fun setSetting(name: String, value: Int) {
        val before = _settings.value.values[name]
        _settings.value = _settings.value.let { it.copy(values = it.values + (name to value), busy = it.busy + name, error = null) }
        viewModelScope.launch {
            val ok = changes.withLock {
                // Recording and sound switch directly (the stock app does that from its live screen too)
                if (name == "rec" || name == "mic") {
                    withContext(Dispatchers.IO) { CamApi.setSetting(name, value) }
                } else {
                    inSettingsMode { CamApi.setSetting(name, value) }
                }
            }
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

    /**
     * Sends a new Wi-Fi name and/or password and restarts the camera's Wi-Fi. The phone drops off the camera's
     * network; [onDone] says whether the camera took the change.
     */
    fun changeWifi(ssid: String?, password: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = changes.withLock {
                withContext(Dispatchers.IO) {
                    val wasRecording = (CamApi.settingValue("rec") ?: _settings.value.values["rec"]) != 0
                    if (wasRecording) CamApi.setSetting("rec", 0)
                    CamApi.enterSettings()
                    val good = CamApi.setWifi(ssid, password)
                    if (good) {
                        // The camera restarts its Wi-Fi now; settings mode is closed and recording restarted once
                        // the phone is back on the camera's network
                        restoreAfterReconnect = wasRecording
                        CamApi.wifiReboot()
                    } else {
                        CamApi.exitSettings()
                        if (wasRecording) CamApi.setSetting("rec", 1)
                    }
                    good
                }
            }
            onDone(ok)
            if (ok) CamConnection.retry()
        }
    }

    /** Erases the camera's card, then reads it again. */
    fun formatCard(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = changes.withLock { inSettingsMode { CamApi.formatCard() } }
            onDone(ok)
            refresh()
        }
    }

    /** Takes a full-resolution photo on the camera and hands it to [onPhoto] once it shows up on the card. */
    fun snapshot(onPhoto: (CamFile?) -> Unit) {
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) {
                val before = runCatching { CamApi.files(Folder.EVENT).filter { it.isPhoto }.map { it.path }.toSet() }.getOrDefault(emptySet())
                if (!CamApi.snapshot()) return@withContext null
                // It takes the camera a moment to write the photo
                repeat(6) {
                    delay(500)
                    val now = runCatching { CamApi.files(Folder.EVENT).filter { it.isPhoto } }.getOrDefault(emptyList())
                    now.filter { it.path !in before }.maxByOrNull { it.startMs }?.let { return@withContext it }
                }
                null
            }
            onPhoto(photo)
            refresh()
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
