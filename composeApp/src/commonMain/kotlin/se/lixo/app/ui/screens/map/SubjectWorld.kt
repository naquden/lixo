package se.lixo.app.ui.screens.map

import androidx.compose.ui.graphics.vector.ImageVector
import se.lixo.app.domain.model.Subject
import se.lixo.app.ui.theme.LixoColors
import se.lixo.app.ui.theme.LixoIcons
import se.lixo.app.ui.theme.LixoStrings

/**
 * Metadata for one subject "world" on the campaign map (plan: "Ämnesväljare med tre världar").
 * Designed so a future fourth world (e.g. geography) only needs one more entry here.
 */
data class SubjectWorld(
    val subject: Subject,
    val title: String,
    val tagline: String,
    val faceColor: androidx.compose.ui.graphics.Color,
    val lipColor: androidx.compose.ui.graphics.Color,
    val tintColor: androidx.compose.ui.graphics.Color,
    val icon: ImageVector
)

val SubjectWorlds: List<SubjectWorld> = listOf(
    SubjectWorld(
        subject = Subject.SWEDISH,
        title = LixoStrings.WORLD_SWEDISH_TITLE,
        tagline = LixoStrings.WORLD_SWEDISH_TAGLINE,
        faceColor = LixoColors.Green,
        lipColor = LixoColors.GreenLip,
        tintColor = LixoColors.GreenTint,
        icon = LixoIcons.Book
    ),
    SubjectWorld(
        subject = Subject.MATH,
        title = LixoStrings.WORLD_MATH_TITLE,
        tagline = LixoStrings.WORLD_MATH_TAGLINE,
        faceColor = LixoColors.Blue,
        lipColor = LixoColors.BlueLip,
        tintColor = LixoColors.BlueTint,
        icon = LixoIcons.Calculator
    ),
    SubjectWorld(
        subject = Subject.ENGLISH,
        title = LixoStrings.WORLD_ENGLISH_TITLE,
        tagline = LixoStrings.WORLD_ENGLISH_TAGLINE,
        faceColor = LixoColors.Purple,
        lipColor = LixoColors.PurpleLip,
        tintColor = LixoColors.PurpleTint,
        icon = LixoIcons.Star
    )
)

fun subjectWorldFor(subject: Subject): SubjectWorld =
    SubjectWorlds.first { it.subject == subject }
