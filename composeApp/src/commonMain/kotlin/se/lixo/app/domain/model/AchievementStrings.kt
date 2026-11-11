package se.lixo.app.domain.model

/**
 * Swedish copy for the plan-014 ladders, used by the data layer when seeding achievements; kept
 * out of the UI theme's `LixoStrings` so data does not depend on UI (doc/plans/018).
 */
object AchievementStrings {
    fun rarityNameSv(rarity: Rarity): String = when (rarity) {
        Rarity.COMMON -> "Vanlig"
        Rarity.UNCOMMON -> "Ovanlig"
        Rarity.RARE -> "Sällsynt"
        Rarity.EPIC -> "Episk"
        Rarity.LEGENDARY -> "Legendarisk"
    }

    /** The chain's display name, used as the base of its tier titles (e.g. "Sagoläsare 1"). */
    fun challengeChainNameSv(action: AchievementAction): String = when (action) {
        AchievementAction.READ_STORY -> "Sagoläsare"
        AchievementAction.SOLVE_MATH -> "Mattekämpe"
        AchievementAction.COLLECT_STARS -> "Stjärnsamlare"
        AchievementAction.PERFECT_LEVEL -> "Perfekt bana"
        AchievementAction.FIRST_TRY -> "Rätt direkt"
        AchievementAction.STREAK_DAY -> "Varje dag"
        AchievementAction.CLOCK_MONEY -> "Klock- & pengaproffs"
        AchievementAction.READ_FAST -> "Snabbläsare"
        AchievementAction.READ_ALOUD -> "Högläsare"
        AchievementAction.EXPLORE_WORLD -> "Världsresenär"
    }

    /** Tier title for a generated (non-handmade) achievement, e.g. "Sagoläsare 3". */
    fun challengeTitleSv(action: AchievementAction, tier: Int): String = "${challengeChainNameSv(action)} $tier"

    /** Short, early-reader instruction text for a challenge card, singular when target == 1. */
    fun challengeShortSv(action: AchievementAction, target: Int): String {
        val n = target
        return when (action) {
            AchievementAction.READ_STORY -> if (n == 1) "Läs 1 saga" else "Läs $n sagor"
            AchievementAction.SOLVE_MATH -> if (n == 1) "Klara 1 mattebana" else "Klara $n mattebanor"
            AchievementAction.COLLECT_STARS -> "Samla $n stjärnor"
            AchievementAction.PERFECT_LEVEL -> if (n == 1) "Få 3 stjärnor på 1 bana" else "Få 3 stjärnor på $n banor"
            AchievementAction.FIRST_TRY -> "Lös $n tal rätt direkt"
            AchievementAction.STREAK_DAY -> "Spela $n dagar i rad"
            AchievementAction.CLOCK_MONEY -> "Lös $n klock- och pengatal"
            AchievementAction.READ_FAST -> if (n == 1) "Läs 1 saga snabbt" else "Läs $n sagor snabbt"
            AchievementAction.READ_ALOUD -> if (n == 1) "Läs 1 saga högt" else "Läs $n sagor högt"
            AchievementAction.EXPLORE_WORLD -> if (n == 1) "Klara 1 flaggbana" else "Klara $n flaggbanor"
        }
    }

    /** Spoken instruction for the TTS "Läs upp" button on a challenge card. */
    fun challengeSpokenSv(action: AchievementAction, target: Int, rarity: Rarity): String {
        val n = target
        val actionSentence = when (action) {
            AchievementAction.READ_STORY -> if (n == 1) "Läs 1 saga." else "Läs $n sagor."
            AchievementAction.SOLVE_MATH -> if (n == 1) "Klara 1 mattebana." else "Klara $n mattebanor."
            AchievementAction.COLLECT_STARS -> "Samla $n stjärnor."
            AchievementAction.PERFECT_LEVEL -> if (n == 1) "Få 3 stjärnor på 1 bana." else "Få 3 stjärnor på $n banor."
            AchievementAction.FIRST_TRY -> "Lös $n tal rätt på första försöket."
            AchievementAction.STREAK_DAY -> "Spela $n dagar i rad."
            AchievementAction.CLOCK_MONEY -> "Lös $n tal om klockan och pengar rätt direkt."
            AchievementAction.READ_FAST -> if (n == 1) "Läs 1 saga snabbt och högt." else "Läs $n sagor snabbt och högt."
            AchievementAction.READ_ALOUD -> if (n == 1) "Läs 1 saga högt utan att hoppa över ord." else "Läs $n sagor högt utan att hoppa över ord."
            AchievementAction.EXPLORE_WORLD -> if (n == 1) "Klara 1 flaggbana." else "Klara $n flaggbanor."
        }
        return "$actionSentence Då får du en ${rarityNameSv(rarity).lowercase()} skatt!"
    }
}
