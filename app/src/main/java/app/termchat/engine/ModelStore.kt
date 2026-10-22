package app.termchat.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class ModelCheck(val textBackends: List<String>, val vision: Boolean, val maxContext: Int)

data class ImportResult(val file: File, val sha256: String)

class ModelStore(private val context: Context) {
    val dir = File(context.filesDir, "models")

    fun find(): File? = dir.listFiles { file -> file.isFile && file.name.endsWith(MODEL_SUFFIX) }
        ?.maxByOrNull { it.lastModified() }

    fun inspect(file: File): ModelCheck = ModelInfo.from(file.absolutePath).use { info ->
        ModelCheck(
            textBackends = info.supportedBackends(Modality.TEXT).map { it.name.lowercase() },
            vision = info.inputModalities().vision,
            maxContext = info.maxContextTokens(),
        )
    }

    suspend fun import(uri: Uri, onProgress: suspend (Int) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0).orEmpty()
                size = if (it.isNull(1)) -1 else it.getLong(1)
            }
        }
        if (!name.endsWith(MODEL_SUFFIX)) throw IOException("not a $MODEL_SUFFIX file: $name")
        dir.mkdirs()
        val free = dir.usableSpace
        if (size > 0 && free < size + SPACE_MARGIN_BYTES) {
            throw IOException("not enough space, need ${(size + SPACE_MARGIN_BYTES) shr 20} MB, free ${free shr 20} MB")
        }
        val part = File(dir, "$name.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("cannot open file")
            input.use { source ->
                part.outputStream().use { target ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var copied = 0L
                    var reported = -1
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        val percent = if (size > 0) (copied * 100 / size).toInt() else 0
                        if (percent / 5 != reported / 5) {
                            reported = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        dir.listFiles { file -> file.name.endsWith(MODEL_SUFFIX) }?.forEach { it.delete() }
        val target = File(dir, name)
        if (!part.renameTo(target)) throw IOException("rename failed")
        ImportResult(target, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val MODEL_SUFFIX = ".litertlm"
        const val SPACE_MARGIN_BYTES = 512L shl 20
        const val BUFFER_BYTES = 1 shl 20
    }
}
