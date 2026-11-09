package se.lixo.app.domain.content

import kotlinx.serialization.Serializable
import se.lixo.app.domain.model.EquipmentSlot

/**
 * Model default only, for code that has no [ActiveContent] at hand (data-class defaults,
 * Firestore/QR parsing). The renderer itself falls back to `content.defaultAvatar` for any
 * unknown avatar id, so this constant never needs to match the bundled pack's actual default.
 */
const val DEFAULT_AVATAR_ID = "avatar_cat"

/**
 * Appearance of an avatar, driven entirely by the content pack (022): [avatarId] is a plain
 * content-pack avatar id (e.g. `"avatar_cat"`), not an enum.
 *
 * [tintHex] null means "use the avatar's `defaultTintHex`".
 *
 * @Serializable is required: this is persisted in `ProfileEntity.appearanceJson`, and embedded
 * in `FriendProfile`, `SyncableProfileSnapshot` and `FirestorePublicAvatarDoc`.
 */
@Serializable
data class AvatarAppearance(
    val avatarId: String = DEFAULT_AVATAR_ID,
    val tintHex: Long? = null,
    val eyeIndex: Int = 0,
    val smileIndex: Int = 0,
    val equipped: Map<EquipmentSlot, String> = emptyMap()
)
