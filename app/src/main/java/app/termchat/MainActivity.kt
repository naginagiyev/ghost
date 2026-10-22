package app.termchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
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
import androidx.compose.ui.Modifier
import app.termchat.ui.ChatsScreen
import app.termchat.ui.Palette
import app.termchat.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    private val controller: ChatController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        controller.state
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
        intent.removeExtra("prompt")
        controller.submit(prompt)
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("termchat", text))
        controller.copied()
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
    LaunchedEffect(Unit) {
        controller.effects.collect { effect ->
            when (effect) {
                Effect.PickImage -> imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                Effect.PickModel -> modelPicker.launch(arrayOf("*/*"))
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
                onStatusClick = controller::showChats,
                onSend = controller::submit,
                onStop = controller::stop,
                onAttach = controller::pickImage,
                onClearAttachment = controller::clearAttachment,
                onCopy = onCopy,
            )
            Screen.CHATS -> ChatsScreen(
                chats = controller.chats,
                onOpen = controller::openChat,
                onDelete = controller::deleteChat,
                onNew = controller::newChat,
                onBack = controller::showSession,
            )
        }
    }
}
