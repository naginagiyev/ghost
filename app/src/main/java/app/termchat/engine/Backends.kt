package app.termchat.engine

import android.content.Context
import app.termchat.BuildConfig
import com.google.ai.edge.litertlm.Backend
import java.io.File

class Backends(context: Context) {
    private val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)

    fun create(name: String): Backend = if (name == GPU) Backend.GPU() else Backend.CPU()

    fun markLoading(name: String) {
        prefs.edit().putString(KEY_LOADING, name).commit()
    }

    fun clearLoading() {
        prefs.edit().remove(KEY_LOADING).commit()
    }

    fun consumeCrash(file: File): String? {
        val crashed = prefs.getString(KEY_LOADING, null) ?: return null
        val editor = prefs.edit().remove(KEY_LOADING).putBoolean(badKey(crashed), true)
        if (prefs.getString(choiceKey(file), null) == crashed) editor.remove(choiceKey(file))
        editor.commit()
        return crashed
    }

    fun isBad(name: String): Boolean = prefs.getBoolean(badKey(name), false)

    fun markBad(name: String) {
        prefs.edit().putBoolean(badKey(name), true).commit()
    }

    fun stored(file: File): String? = prefs.getString(choiceKey(file), null)?.takeIf { !isBad(it) }

    fun store(file: File, name: String) {
        prefs.edit().putString(choiceKey(file), name).commit()
    }

    fun reset(file: File) {
        prefs.edit().remove(choiceKey(file)).remove(badKey(CPU)).remove(badKey(GPU)).commit()
    }

    private fun choiceKey(file: File) = "backend_${file.length()}_${BuildConfig.VERSION_CODE}"

    private fun badKey(name: String) = "bad_$name"

    companion object {
        const val CPU = "cpu"
        const val GPU = "gpu"
        private const val KEY_LOADING = "loading_in_progress"
    }
}
