package com.timcordova.quartzcam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.cam.Folder
import com.timcordova.quartzcam.cam.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class CamViewModel : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Read the card whenever the camera comes (back) into reach
        viewModelScope.launch {
            CamConnection.state
                .map { it.status == CamConnection.Status.CONNECTED }
                .distinctUntilChanged()
                .collect { connected -> if (connected) refresh() }
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
