package com.hippo.ehviewer.easytier

import android.content.Context
import android.os.Build
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.EasyTierManager
import com.ehviewer.core.util.logcat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority

/**
 * Process-scoped EasyTier controller. Survives Activity recreation so the tunnel
 * stays up while browsing SMB galleries.
 */
object EasyTierRuntime {
    const val INSTANCE_NAME = "Default"

    data class State(
        val supported: Boolean = isArm64Device(),
        val running: Boolean = false,
        /** True after start() succeeded; may briefly lack status JSON while connecting. */
        val connectingOrRunning: Boolean = false,
        val statusJson: String? = null,
        val lastError: String? = null,
        val config: EasyTierConfigUiState = EasyTierConfigUiState(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var store: EasyTierConfigStore? = null

    @Volatile
    private var manager: EasyTierManager? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            val m = manager
            val ctx = appContext
            if (m == null || ctx == null) return
            val json = m.latestNetworkInfoJson
            val running = m.running
            _state.update {
                it.copy(
                    running = running && !json.isNullOrEmpty(),
                    connectingOrRunning = running,
                    statusJson = json,
                )
            }
            if (running) {
                mainHandler.postDelayed(this, STATUS_POLL_MS)
            }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        store = EasyTierConfigStore(app)
        _state.update {
            it.copy(
                supported = isArm64Device(),
                config = store!!.loadUiState(),
            )
        }
    }

    fun loadConfig(): EasyTierConfigUiState {
        val s = store ?: return EasyTierTomlCodec.parseConfig(EasyTierTomlCodec.defaultToml())
        val config = s.loadUiState()
        _state.update { it.copy(config = config) }
        return config
    }

    fun saveConfig(config: EasyTierConfigUiState) {
        val s = store ?: return
        s.saveUiState(config)
        _state.update { it.copy(config = config) }
        // Re-init manager with new config (stops if was running — match moonlight).
        val wasRunning = manager?.running == true
        rebuildManager()
        if (wasRunning) {
            // User must start again after config change while running, or auto-restart:
            // moonlight re-inits and leaves stopped until user starts. We match that.
            _state.update {
                it.copy(
                    running = false,
                    connectingOrRunning = false,
                    statusJson = null,
                    lastError = null,
                )
            }
        }
    }

    /**
     * Start EasyTier after VPN permission has been granted.
     * @return true if native start succeeded
     */
    fun start(): Boolean {
        val ctx = appContext
        if (ctx == null) {
            _state.update { it.copy(lastError = "not initialized") }
            return false
        }
        if (!isArm64Device()) {
            _state.update { it.copy(lastError = "arm64 required", supported = false) }
            return false
        }
        if (!EasyTierJNI.ensureLoaded()) {
            val err = EasyTierJNI.libraryLoadError() ?: "native load failed"
            _state.update { it.copy(lastError = err) }
            logcat(TAG, LogPriority.ERROR) { err }
            return false
        }
        rebuildManager()
        val m = manager ?: return false
        val ok = m.start()
        if (ok) {
            _state.update {
                it.copy(
                    connectingOrRunning = true,
                    running = false,
                    lastError = null,
                    statusJson = null,
                )
            }
            mainHandler.removeCallbacks(statusPoll)
            mainHandler.post(statusPoll)
        } else {
            val err = EasyTierJNI.getLastError() ?: "start failed"
            _state.update {
                it.copy(
                    connectingOrRunning = false,
                    running = false,
                    lastError = err,
                )
            }
        }
        return ok
    }

    fun stop() {
        mainHandler.removeCallbacks(statusPoll)
        manager?.stop()
        manager = null
        _state.update {
            it.copy(
                running = false,
                connectingOrRunning = false,
                statusJson = null,
                lastError = null,
            )
        }
    }

    fun refreshStatus() {
        val json = manager?.latestNetworkInfoJson
        _state.update {
            it.copy(
                statusJson = json,
                running = manager?.running == true && !json.isNullOrEmpty(),
                connectingOrRunning = manager?.running == true,
            )
        }
    }

    private fun rebuildManager() {
        val ctx = appContext ?: return
        val s = store ?: EasyTierConfigStore(ctx).also { store = it }
        manager?.stop()
        manager = EasyTierManager(ctx, INSTANCE_NAME, s.loadToml())
    }

    fun isArm64Device(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private const val TAG = "EasyTierRuntime"
    private const val STATUS_POLL_MS = 1500L
}
