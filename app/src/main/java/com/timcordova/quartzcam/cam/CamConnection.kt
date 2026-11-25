package com.timcordova.quartzcam.cam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the dash cam's Wi-Fi and sends all of the app's traffic over it.
 *
 * The camera's Wi-Fi has no internet, so Android keeps using mobile data for everything by default and requests
 * to the camera go nowhere. Binding the app to the camera's network fixes that for this app only; every other app
 * keeps using mobile data as usual.
 */
object CamConnection {
    enum class Status { SEARCHING, CONNECTED, NOT_FOUND }

    data class State(
        val status: Status,
        val info: ProductInfo? = null,
    )

    private val _state = MutableStateFlow(State(Status.SEARCHING))
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Wi-Fi networks the phone is on right now (usually just one). */
    private val wifi = LinkedHashSet<Network>()
    private var bound: Network? = null
    private var probe: Job? = null

    fun start(context: Context) {
        if (callback != null) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        this.cm = cm
        // Any Wi-Fi, with or without internet (the camera's has none)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(wifi) { wifi += network }
                retry()
            }

            override fun onLost(network: Network) {
                synchronized(wifi) { wifi -= network }
                if (network == bound) {
                    cm.bindProcessToNetwork(null)
                    bound = null
                }
                retry()
            }
        }
        callback = cb
        cm.registerNetworkCallback(request, cb)
        retry()
    }

    /** Looks for the camera on each Wi-Fi network the phone is on. */
    fun retry() {
        probe?.cancel()
        probe = scope.launch {
            if (_state.value.status != Status.CONNECTED) _state.value = State(Status.SEARCHING)
            // Give a network that just appeared a moment to get its address
            delay(400)
            val networks = synchronized(wifi) { wifi.toList() }
            for (n in networks) {
                if (!reachable(n)) continue
                cm?.bindProcessToNetwork(n)
                bound = n
                val info = runCatching { CamApi.productInfo() }.getOrNull()
                _state.value = State(Status.CONNECTED, info)
                return@launch
            }
            if (bound != null) {
                cm?.bindProcessToNetwork(null)
                bound = null
            }
            _state.value = State(Status.NOT_FOUND)
        }
    }

    /** Whether the camera answers on [network]. */
    private fun reachable(network: Network): Boolean = runCatching {
        val conn = network.openConnection(URL(CamApi.base + "/app/getproductinfo")) as HttpURLConnection
        conn.connectTimeout = 2500
        conn.readTimeout = 2500
        try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** Called when a request to the camera fails, in case the Wi-Fi went away. */
    fun reportFailure() {
        if (_state.value.status == Status.CONNECTED) retry()
    }
}
