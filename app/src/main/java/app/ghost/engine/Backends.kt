package app.ghost.engine

import android.content.Context
import android.os.Build
import app.ghost.BuildConfig
import com.google.ai.edge.litertlm.Backend
import java.io.File

class Backends(context: Context) {
    private val prefs by lazy { context.getSharedPreferences("engine", Context.MODE_PRIVATE) }
    private val environment = "${BuildConfig.VERSION_CODE}_${Build.FINGERPRINT.hashCode()}"

    fun create(name: String): Backend = if (name == GPU) Backend.GPU() else Backend.CPU()

    fun choose(file: File, candidates: List<String>): String {
        stored(file)?.takeIf { it in candidates }?.let { return it }
        if (candidates.size == 1) {
            store(file, candidates[0])
            return candidates[0]
        }
        val ordered = candidates.sortedByDescending { it == GPU }
        ordered.firstOrNull { !measured(file, it) }?.let { return it }
        val best = ordered.minBy { typicalTurnSeconds(file, it) }
        store(file, best)
        return best
    }

    fun recordPrefill(file: File, name: String, tokensPerSecond: Double) = record(file, name, PREFILL, tokensPerSecond, PREFILL_SAMPLES)

    fun recordDecode(file: File, name: String, tokensPerSecond: Double) = record(file, name, DECODE, tokensPerSecond, DECODE_SAMPLES)

    private fun record(file: File, name: String, kind: String, tokensPerSecond: Double, needed: Int) {
        if (stored(file) != null || tokensPerSecond <= 0) return
        val count = prefs.getInt(countKey(file, name, kind), 0)
        if (count >= needed) return
        val average = (prefs.getFloat(speedKey(file, name, kind), 0f) * count + tokensPerSecond) / (count + 1)
        prefs.edit()
            .putInt(countKey(file, name, kind), count + 1)
            .putFloat(speedKey(file, name, kind), average.toFloat())
            .commit()
    }

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

    fun store(file: File, name: String) {
        prefs.edit().putString(choiceKey(file), name).commit()
    }

    private fun stored(file: File): String? = prefs.getString(choiceKey(file), null)?.takeIf { !isBad(it) }

    private fun measured(file: File, name: String): Boolean =
        prefs.getInt(countKey(file, name, PREFILL), 0) >= PREFILL_SAMPLES &&
            prefs.getInt(countKey(file, name, DECODE), 0) >= DECODE_SAMPLES

    private fun typicalTurnSeconds(file: File, name: String): Double {
        val prefill = prefs.getFloat(speedKey(file, name, PREFILL), 0f).toDouble()
        val decode = prefs.getFloat(speedKey(file, name, DECODE), 0f).toDouble()
        if (prefill <= 0 || decode <= 0) return Double.MAX_VALUE
        return TYPICAL_PROMPT_TOKENS / prefill + TYPICAL_ANSWER_TOKENS / decode
    }

    private fun choiceKey(file: File) = "backend_${file.length()}_$environment"

    private fun badKey(name: String) = "bad_${name}_$environment"

    private fun countKey(file: File, name: String, kind: String) = "${kind}_count_${name}_${file.length()}_$environment"

    private fun speedKey(file: File, name: String, kind: String) = "${kind}_speed_${name}_${file.length()}_$environment"

    companion object {
        const val CPU = "cpu"
        const val GPU = "gpu"
        private const val KEY_LOADING = "loading_in_progress"
        private const val PREFILL = "prefill"
        private const val DECODE = "decode"
        private const val PREFILL_SAMPLES = 2
        private const val DECODE_SAMPLES = 3
        private const val TYPICAL_PROMPT_TOKENS = 300.0
        private const val TYPICAL_ANSWER_TOKENS = 200.0
    }
}
