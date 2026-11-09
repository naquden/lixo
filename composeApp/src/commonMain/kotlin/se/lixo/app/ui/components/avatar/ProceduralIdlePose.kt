package se.lixo.app.ui.components.avatar

import kotlin.math.PI
import kotlin.math.sin

/**
 * Procedural rigid-body idle animation state per skeleton body part (`project.md` §4:
 * "Animationer (MVP): En idle-animation via procedurell rigid-body-animation per kroppsdel").
 */
data class ProceduralIdlePose(
    val headBobOffsetY: Float,
    val torsoBreathScaleY: Float,
    val leftArmAngleDeg: Float,
    val rightArmAngleDeg: Float,
    val tailWagAngleDeg: Float,
    val legendaryAuraPhase: Float
)

/**
 * Evaluates deterministic 60 FPS procedural rigid-body idle transforms at time `timeSeconds`.
 */
fun computeProceduralIdlePose(
    timeSeconds: Float,
    hasLegendaryEquipped: Boolean = false
): ProceduralIdlePose {
    val omega = (2.0 * PI * 0.85).toFloat() // ~0.85 Hz gentle breathing rhythm
    val speedBoost = if (hasLegendaryEquipped) 1.25f else 1.0f
    val t = timeSeconds * omega * speedBoost

    return ProceduralIdlePose(
        headBobOffsetY = 6.5f * sin(t),
        torsoBreathScaleY = 1.0f + 0.025f * sin(t - 0.3f),
        leftArmAngleDeg = 7.5f * sin(t + 0.6f),
        rightArmAngleDeg = -7.5f * sin(t + 0.6f),
        tailWagAngleDeg = 18.0f * sin(t * 1.6f),
        legendaryAuraPhase = (timeSeconds * 0.5f) % 1.0f
    )
}
