package app.ghost

import android.app.Application
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import app.ghost.data.Db
import app.ghost.data.DocStore
import app.ghost.data.ImageStore
import app.ghost.data.Repository
import app.ghost.engine.Backends
import app.ghost.engine.ContextSizer
import app.ghost.engine.LiteRtEngine
import app.ghost.engine.LlmEngine
import app.ghost.engine.ModelLifecycle
import app.ghost.engine.ModelStore
import app.ghost.engine.Speculation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var engine: LlmEngine
        private set
    lateinit var repository: Repository
        private set
    lateinit var imageStore: ImageStore
        private set
    lateinit var modelStore: ModelStore
        private set
    lateinit var docStore: DocStore
        private set
    private lateinit var modelLifecycle: ModelLifecycle

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        repository = Repository(Db.create(this).chatDao())
        imageStore = ImageStore(this)
        modelStore = ModelStore(this)
        docStore = DocStore(this)
        engine = LiteRtEngine(this, modelStore, Backends(this), ContextSizer(this), Speculation(this))
        modelLifecycle = ModelLifecycle(engine, scope).also { it.attach() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor) { status ->
                engine.setThrottled(status >= PowerManager.THERMAL_STATUS_SEVERE)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        modelLifecycle.onTrimMemory(level)
    }
}
