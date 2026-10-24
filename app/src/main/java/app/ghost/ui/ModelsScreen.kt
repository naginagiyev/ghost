package app.ghost.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghost.engine.ModelFile

@Composable
fun ModelsScreen(
    models: List<ModelFile>,
    onSwitch: (ModelFile) -> Unit,
    onDelete: (ModelFile) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirming by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.block),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = Spacing.tight)) {
                BasicText("~/models  (${models.size})", style = BodyStyle.copy(color = Palette.accent))
                BasicText("tap to switch · hold to delete", style = SmallStyle)
            }
        }
        itemsIndexed(models, key = { _, model -> model.file.name }) { index, model ->
            if (confirming == model.file.name) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    BasicText("delete ${model.label}?", style = BodyStyle.copy(color = Palette.warning))
                    BasicText("the file is removed from the phone, chats stay", style = SmallStyle)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TermButton("delete", Palette.error) {
                            confirming = null
                            onDelete(model)
                        }
                        TermButton("cancel", Palette.text) { confirming = null }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { onSwitch(model) },
                            onLongClick = { if (!model.active) confirming = model.file.name },
                        )
                        .padding(vertical = 10.dp),
                ) {
                    BasicText(
                        if (model.active) " ●  " else " %02d ".format(index + 1),
                        style = BodyStyle.copy(color = if (model.active) Palette.user else Palette.dim),
                    )
                    BasicText(
                        model.label,
                        Modifier.weight(1f),
                        style = BodyStyle.copy(color = if (model.active) Palette.text else Palette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        "%.1f GB".format(model.file.length() / 1_073_741_824.0).padStart(9),
                        style = BodyStyle.copy(color = Palette.dim),
                    )
                }
            }
        }
        item(key = "import") {
            BasicText(
                "  +  import a .litertlm file",
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null, onClick = onImport)
                    .padding(vertical = 12.dp),
                style = BodyStyle.copy(color = Palette.user),
            )
        }
    }
}
