package app.ghost.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import androidx.compose.runtime.Immutable
import app.ghost.Config
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

@Immutable
data class DocInfo(val path: String, val name: String, val chars: Int, val truncated: Boolean) {
    val label: String get() = "[doc $name ~${tokenText(chars)} tokens${if (truncated) ", cut" else ""}]"
}

fun tokenText(chars: Int): String {
    val tokens = (chars / Config.CHARS_PER_TOKEN).toInt()
    return if (tokens < 1000) "${maxOf(tokens, 1)}" else "${(tokens + 500) / 1000}k"
}

class DocStore(private val context: Context) {
    private val dir by lazy { File(context.filesDir, "docs") }
    private var pdfReady = false

    suspend fun save(uri: Uri, maxChars: Int): DocInfo = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            when (extension) {
                "txt", "md", "markdown" -> input.readBytes().toString(Charsets.UTF_8)
                "docx" -> docxText(input)
                "pdf" -> pdfText(input)
                else -> throw IOException("unsupported file type .$extension, use md, txt, pdf or docx")
            }
        } ?: throw IOException("cannot open $name")
        val clean = text.replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) throw IOException("no text found in $name, for a scanned page send a photo instead")
        val kept = clean.take(maxChars)
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.txt")
        file.writeText(kept)
        DocInfo(file.absolutePath, name, kept.length, kept.length < clean.length)
    }

    fun read(path: String): String = File(path).takeIf { it.exists() }?.readText().orEmpty()

    fun info(path: String, name: String): DocInfo = DocInfo(path, name, File(path).length().toInt(), false)

    suspend fun delete(path: String) = withContext(Dispatchers.IO) { File(path).delete() }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun pdfText(input: InputStream): String {
        if (!pdfReady) {
            PDFBoxResourceLoader.init(context)
            pdfReady = true
        }
        return try {
            PDDocument.load(input).use { PDFTextStripper().getText(it) }
        } catch (e: Exception) {
            throw IOException("cannot read this pdf (${e.message ?: "unknown error"})")
        }
    }

    private fun docxText(input: InputStream): String {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: throw IOException("not a valid docx file")
            if (entry.name == "word/document.xml") return wordXmlText(zip)
        }
    }

    private fun wordXmlText(input: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val text = StringBuilder()
        var inText = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "t" -> inText = true
                    "tab" -> text.append('\t')
                    "br" -> text.append('\n')
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p" -> text.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) text.append(parser.text)
                XmlPullParser.END_DOCUMENT -> return text.toString()
            }
        }
    }
}
