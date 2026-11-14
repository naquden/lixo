package se.lixo.app.domain.model

/** Outcome of renaming a profile (doc/plans/050). */
enum class RenameProfileResult { RENAMED, UNCHANGED, BLANK, TAKEN, UNKNOWN_PROFILE }

/** Trimmed and capped to [BalanceConfig.PROFILE_NAME_MAX_LENGTH]; the form every stored displayName has. */
fun normalizeProfileName(raw: String): String =
    raw.trim().take(BalanceConfig.PROFILE_NAME_MAX_LENGTH).trimEnd()

/** True if another profile (not [excludingProfileId]) already has [name], compared after normalizing, ignoring case. */
fun isProfileNameTaken(profiles: List<ChildProfile>, name: String, excludingProfileId: String? = null): Boolean {
    val wanted = normalizeProfileName(name).lowercase()
    return profiles.any { it.id != excludingProfileId && normalizeProfileName(it.displayName).lowercase() == wanted }
}

/** [name] if free, else "<name> 2", "<name> 3", … (base shortened so the result fits the max length). */
fun uniqueProfileName(profiles: List<ChildProfile>, name: String, excludingProfileId: String? = null): String {
    val base = normalizeProfileName(name)
    if (!isProfileNameTaken(profiles, base, excludingProfileId)) return base
    var n = 2
    while (true) {
        val suffix = " $n"
        val candidate = base.take(BalanceConfig.PROFILE_NAME_MAX_LENGTH - suffix.length).trimEnd() + suffix
        if (!isProfileNameTaken(profiles, candidate, excludingProfileId)) return candidate
        n++
    }
}
