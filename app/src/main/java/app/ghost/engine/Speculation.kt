package app.ghost.engine

import android.content.Context
import android.os.Build
import app.ghost.BuildConfig
import java.io.File

class Speculation(context: Context) {
    private val prefs by lazy { context.getSharedPreferences("speculation", Context.MODE_PRIVATE) }
    private val environment = "${BuildConfig.VERSION_CODE}_${Build.FINGERPRINT.hashCode()}"

    fun enabled(file: File, backend: String): Boolean {
        val key = choiceKey(file, backend)
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        if (count(file, backend, false) < SAMPLES) return false
        if (count(file, backend, true) < SAMPLES) return true
        val faster = speed(file, backend, true) > speed(file, backend, false) * MIN_GAIN
        prefs.edit().putBoolean(key, faster).commit()
        return faster
    }

    fun record(file: File, backend: String, on: Boolean, tokensPerSecond: Double) {
        if (prefs.contains(choiceKey(file, backend)) || tokensPerSecond <= 0) return
        val count = count(file, backend, on)
        if (count >= SAMPLES) return
        val average = (speed(file, backend, on) * count + tokensPerSecond) / (count + 1)
        prefs.edit()
            .putInt(countKey(file, backend, on), count + 1)
            .putFloat(speedKey(file, backend, on), average.toFloat())
            .commit()
    }

    fun markFailed(file: File, backend: String) {
        prefs.edit().putBoolean(choiceKey(file, backend), false).commit()
    }

    private fun count(file: File, backend: String, on: Boolean) = prefs.getInt(countKey(file, backend, on), 0)

    private fun speed(file: File, backend: String, on: Boolean) = prefs.getFloat(speedKey(file, backend, on), 0f).toDouble()

    private fun choiceKey(file: File, backend: String) = "on_${backend}_${file.length()}_$environment"

    private fun countKey(file: File, backend: String, on: Boolean) = "count_${on}_${backend}_${file.length()}_$environment"

    private fun speedKey(file: File, backend: String, on: Boolean) = "speed_${on}_${backend}_${file.length()}_$environment"

    private companion object {
        const val SAMPLES = 3
        const val MIN_GAIN = 1.1
    }
}
