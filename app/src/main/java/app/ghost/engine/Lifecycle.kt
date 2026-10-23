package app.ghost.engine

import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.ghost.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ModelLifecycle(private val engine: LlmEngine, private val scope: CoroutineScope) : DefaultLifecycleObserver {
    private var pendingUnload: Job? = null
    private var visible = false

    fun attach() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) {
        visible = true
        pendingUnload?.cancel()
        pendingUnload = null
        if (engine.state.value == EngineState.Unloaded) engine.load()
    }

    override fun onStop(owner: LifecycleOwner) {
        visible = false
        engine.stop()
        pendingUnload?.cancel()
        pendingUnload = scope.launch(Dispatchers.Main) {
            delay(Config.UNLOAD_GRACE_MS)
            engine.unload()
        }
    }

    fun onTrimMemory(level: Int) {
        if (visible || level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
        pendingUnload?.cancel()
        pendingUnload = null
        engine.unload()
    }
}
