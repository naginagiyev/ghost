package app.ghost

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.ghost.ui.ChatsScreen
import app.ghost.ui.InputActions
import app.ghost.ui.ModelsScreen
import app.ghost.ui.Palette
import app.ghost.ui.TerminalScreen
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val controller: ChatController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            controller.state.map { it.busy }.distinctUntilChanged().collect { busy ->
                window.attributes = window.attributes.apply { preferredRefreshRate = if (busy) BUSY_REFRESH_RATE else 0f }
            }
        }
        if (savedInstanceState == null) handleDebugPrompt(intent)
        setContent { Root(controller, ::copyToClipboard) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugPrompt(intent)
    }

    private fun handleDebugPrompt(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val prompt = intent.getStringExtra("prompt") ?: return
        val image = intent.getStringExtra("image")
        val doc = intent.getStringExtra("doc")
        intent.removeExtra("prompt")
        intent.removeExtra("image")
        intent.removeExtra("doc")
        when {
            image != null -> controller.onImagePicked(Uri.fromFile(File(image)), prompt)
            doc != null -> controller.onDocumentPicked(Uri.fromFile(File(doc)), prompt)
            prompt == "/new" -> controller.newChat()
            else -> controller.submit(prompt)
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ghost", text))
        controller.copied()
    }

    private companion object {
        const val BUSY_REFRESH_RATE = 60f
    }
}

@Composable
private fun Root(controller: ChatController, onCopy: (String) -> Unit) {
    val state by controller.state.collectAsState()
    val listState = rememberLazyListState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(controller::onImagePicked)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::onModelPicked)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.onDocumentPicked(it) }
    }
    val context = LocalContext.current
    val photoFile by remember { lazy { File(File(context.cacheDir, "camera"), "photo.jpg") } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) controller.onPhotoTaken(photoFile) else photoFile.delete()
    }
    LaunchedEffect(Unit) {
        controller.effects.collect { effect ->
            when (effect) {
                Effect.PickImage -> imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                Effect.TakePhoto -> {
                    photoFile.parentFile?.mkdirs()
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photoFile)
                    try {
                        camera.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        controller.cameraUnavailable()
                    }
                }
                Effect.PickModel -> modelPicker.launch(arrayOf("*/*"))
                Effect.PickDocument -> documentPicker.launch(DOCUMENT_TYPES)
                Effect.ScrollToBottom -> listState.scrollToItem(0)
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        when (state.screen) {
            Screen.SESSION -> TerminalScreen(
                state = state,
                stream = controller.stream,
                listState = listState,
                thumbnail = controller::thumbnail,
                onChatsClick = controller::showChats,
                onModelClick = controller::showModels,
                onCopy = onCopy,
                onEdit = controller::startEdit,
                actions = InputActions(
                    onSend = controller::submit,
                    onStop = controller::stop,
                    onGallery = controller::pickImage,
                    onCamera = controller::takePhoto,
                    onFile = controller::pickDocument,
                    onClearAttachment = controller::clearAttachment,
                    onClearDocument = controller::clearDocument,
                    onCancelEdit = controller::cancelEdit,
                    onTyping = controller::typing,
                ),
            )
            Screen.CHATS -> ChatsScreen(
                chats = controller.chats,
                onOpen = controller::openChat,
                onDelete = controller::deleteChat,
                onRename = controller::renameChat,
                onPin = controller::setPinned,
                onNew = controller::newChat,
                onBack = controller::showSession,
            )
            Screen.MODELS -> ModelsScreen(
                models = state.models,
                onSwitch = controller::switchModel,
                onDelete = controller::deleteModel,
                onImport = controller::importModel,
                onBack = controller::showSession,
            )
        }
    }
}

private val DOCUMENT_TYPES = arrayOf(
    "application/pdf",
    "text/*",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/octet-stream",
)
