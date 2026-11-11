package se.lixo.app.ui.screens.math

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Plan 040: prompt in Normal weight with only [emphasis] ExtraBold; null => caller keeps the
 * plain fully-bold rendering.
 */
fun promptWithEmphasis(prompt: String, emphasis: String?): AnnotatedString? {
    if (emphasis.isNullOrEmpty()) return null
    val start = prompt.indexOf(emphasis)
    if (start < 0) return null
    return buildAnnotatedString {
        append(prompt)
        addStyle(SpanStyle(fontWeight = FontWeight.Normal), 0, prompt.length)
        addStyle(SpanStyle(fontWeight = FontWeight.ExtraBold), start, start + emphasis.length)
    }
}
