package app.termchat

import android.app.Application
import android.os.StrictMode
import app.termchat.data.Db
import app.termchat.data.ImageStore
import app.termchat.data.Repository
import app.termchat.engine.Backends
import app.termchat.engine.LiteRtEngine
import app.termchat.engine.LlmEngine
import app.termchat.engine.ModelLifecycle
import app.termchat.engine.ModelStore
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
    private lateinit var modelLifecycle: ModelLifecycle

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        repository = Repository(Db.create(this).chatDao())
        imageStore = ImageStore(this)
        modelStore = ModelStore(this)
        engine = LiteRtEngine(this, modelStore, Backends(this))
        modelLifecycle = ModelLifecycle(engine, scope).also { it.attach() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        modelLifecycle.onTrimMemory(level)
    }
}
