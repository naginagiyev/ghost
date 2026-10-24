package app.ghost.engine

import android.content.Context
import android.os.Build
import app.ghost.BuildConfig
import app.ghost.Config
import java.io.File

class ContextSizer(context: Context) {
    private val prefs by lazy { context.getSharedPreferences("context", Context.MODE_PRIVATE) }
    private val environment = "${BuildConfig.VERSION_CODE}_${Build.FINGERPRINT.hashCode()}"

    fun choose(file: File, modelMax: Int, totalMemory: Long): Int {
        val budget = (totalMemory * Config.CONTEXT_MEMORY_SHARE).toLong()
        val cap = prefs.getInt(failedKey(file), Int.MAX_VALUE)
        var best = Config.CONTEXT_TIERS.first()
        for (tier in Config.CONTEXT_TIERS.drop(1)) {
            if (tier > modelMax || tier >= cap) break
            val predicted = predict(file, tier) ?: break
            if (predicted > budget) break
            best = tier
        }
        return best
    }

    fun record(file: File, tier: Int, pssBytes: Long) {
        val key = pssKey(file, tier)
        prefs.edit().putLong(key, maxOf(prefs.getLong(key, 0), pssBytes)).commit()
    }

    fun markFailed(file: File, tier: Int) {
        val cap = minOf(prefs.getInt(failedKey(file), Int.MAX_VALUE), tier)
        prefs.edit().putInt(failedKey(file), cap).commit()
    }

    private fun predict(file: File, tier: Int): Long? {
        measured(file, tier)?.let { return it }
        val known = Config.CONTEXT_TIERS.filter { it < tier }.mapNotNull { t -> measured(file, t)?.let { t to it } }
        val (baseTier, basePss) = known.lastOrNull() ?: return null
        return basePss + (tier - baseTier) * bytesPerToken(file)
    }

    private fun bytesPerToken(file: File): Long {
        val known = Config.CONTEXT_TIERS.mapNotNull { t -> measured(file, t)?.let { t to it } }
        if (known.size < 2) return Config.CONTEXT_BYTES_PER_TOKEN
        val (lowTier, lowPss) = known.first()
        val (highTier, highPss) = known.last()
        return maxOf(Config.CONTEXT_BYTES_PER_TOKEN / 2, (highPss - lowPss) / (highTier - lowTier))
    }

    private fun measured(file: File, tier: Int): Long? = prefs.getLong(pssKey(file, tier), 0).takeIf { it > 0 }

    private fun pssKey(file: File, tier: Int) = "pss_${tier}_${file.length()}_$environment"

    private fun failedKey(file: File) = "failed_${file.length()}_$environment"
}
