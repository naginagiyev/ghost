package app.ghost.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.ghost.R

object Palette {
    val background = Color(0xFF050607)
    val text = Color(0xFFC9D1D9)
    val dim = Color(0xFF5C6773)
    val user = Color(0xFF7EE787)
    val accent = Color(0xFF56D4DD)
    val math = Color(0xFFD2A8FF)
    val warning = Color(0xFFE3B341)
    val error = Color(0xFFFF7B72)
    val codeBackground = Color(0xFF0D1117)
    val border = Color(0xFF1F2A33)
}

object Spacing {
    val screen = 12.dp
    val block = 10.dp
    val tight = 4.dp
}

val TermFont = FontFamily(
    Font(R.font.jbm_regular, FontWeight.Normal),
    Font(R.font.jbm_bold, FontWeight.Bold),
    Font(R.font.jbm_italic, FontWeight.Normal, FontStyle.Italic),
)

val BodyStyle = TextStyle(
    fontFamily = TermFont,
    fontSize = 14.sp,
    lineHeight = 1.45.em,
    color = Palette.text,
)

val SmallStyle = BodyStyle.copy(fontSize = 12.sp, color = Palette.dim)
