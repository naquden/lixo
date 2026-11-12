package se.lixo.app.ui.theme

import se.lixo.app.domain.model.AchievementStrings
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.RewardSource
import se.lixo.app.domain.model.Subject

/**
 * Strongly-typed Swedish localization catalog.
 * Clean, concise, child- and parent-friendly UX copy without emoji clutter.
 */
object LixoStrings {
    const val APP_NAME = "Lixo"

    // Bottom Navigation
    const val NAV_LEARN = "Lär dig"
    const val NAV_AVATAR = "Avatar"
    const val NAV_TROPHIES = "Troféer"
    const val NAV_PARENT_SETTINGS = "Förälder"

    // Onboarding & Profile Selection
    const val PROFILE_SELECT_TITLE = "Vem ska spela?"
    const val PROFILE_TAP_TO_START = "Välj profil för att fortsätta"
    const val PROFILE_ADD_SIBLING = "Ny profil"
    const val PROFILE_NEW_NAME_LABEL = "Barnets namn"
    const val PROFILE_CREATE_BTN = "Skapa profil"
    const val PROFILE_NAME_PLACEHOLDER = "T.ex. Alva"
    const val FIRST_PROFILE_TITLE = "Välkommen till Lixo!"
    const val FIRST_PROFILE_SUBTITLE = "Vad heter du?"
    const val FIRST_PROFILE_AGE_LABEL = "Hur gammal är du?"

    // Tutorial
    const val TUTORIAL_WELCOME_TITLE = "Välkommen till Lixo"
    const val TUTORIAL_MIC_PROMPT =
        "När du läser sagan högt lyssnar läsassistenten och följer med bland orden i realtid."
    const val TUTORIAL_ALLOW_MIC_BTN = "Starta med röst"
    const val TUTORIAL_MANUAL_FALLBACK_BTN = "Läs själv"
    const val TUTORIAL_SKIP_BTN = "Hoppa över"
    const val TUTORIAL_REWARD_BANNER = "Bra jobbat! Du låste upp din första hatt."
    const val TUTORIAL_CONTINUE_TO_MAP_BTN = "Börja lära dig"

    // Campaign Map
    const val MAP_STARS_LABEL = "stjärnor"
    const val MAP_STREAK_LABEL = "dagar"
    const val MAP_TIMED_BADGE = "Tidsutmaning"
    const val MAP_PLAY_LEVEL_BTN = "Starta bana"
    const val MAP_REPLAY_GIFT_HINT = "Chans på present"
    const val MAP_MAXED_STARS_HINT = "Klarad · Spela igen för gåva"
    const val MAP_LOCKED_HINT = "Klara banan innan först"

    // Campaign Map - Subject Worlds (ämnesväljare)
    const val WORLD_SWEDISH_TITLE = "Sagoskogen"
    const val WORLD_SWEDISH_TAGLINE = "Läsning & berättelser"
    const val WORLD_MATH_TITLE = "Sifferdalen"
    const val WORLD_MATH_TAGLINE = "Tal, klockan & mattegåtor"
    const val WORLD_ENGLISH_TITLE = "Glosornas Hamn"
    const val WORLD_ENGLISH_TAGLINE = "Engelska ord & fraser"
    const val WORLD_GEOGRAPHY_TITLE = "Världsresan"
    const val WORLD_GEOGRAPHY_TAGLINE = "Länder & flaggor"
    const val WORLD_SELECTOR_HEADER_PREFIX = "VÄRLD"

    // Geography (flag) activity
    const val FLAG_CHOICE_A11Y = "Flaggalternativ"

    // Reading Activity
    const val READING_MODE_SELECT_TITLE = "Hur vill du läsa?"
    const val READING_MODE_AI_LABEL = "AI-Läsassistent (1–3 stjärnor)"
    const val READING_MODE_MANUAL_LABEL = "Läsa själv (1 stjärna)"
    const val READING_MODE_LOCKED_BADGE = "Läsläge"
    const val READING_SIMULATE_VOICE_BTN = "Läs nästa ord"
    /** Fallback shown when stalled on the last word (the recognizer probably missed it). */
    const val READING_LAST_WORD_MISSED_BTN = "Jag har läst klart"
    const val READING_HEARD_WORD_LABEL = "Ordet:"
    const val READING_HEARD_AI_LABEL = "Jag hörde:"
    const val READING_HEARD_AI_FIRST_LABEL = "Först hörde jag:"
    const val READING_HEARD_NOTHING = "Jag hörde inget tydligt"
    const val READING_HEARD_TRY_AGAIN_HINT = "Säg ordet igen – jag lyssnar!"
    const val READING_MANUAL_DONE_BTN = "Klar med läsningen"
    const val READING_MANUAL_CHARGING_LABEL = "Läser..."
    const val READING_STUCK_TITLE = "Hjälp med ordet"
    const val READING_HELP_LISTEN = "Lyssna"
    const val READING_HELP_SYLLABLES = "Stavelser"
    const val READING_HELP_EXPLANATION = "Förklaring"
    const val READING_HELP_SKIP_NO_PENALTY = "Gå vidare"
    const val READING_TAP_WORD_HINT = "Gula ord kan behöva övas – tryck på ett ord för hjälp"
    const val READING_READ_YELLOW_WORDS = "Läs de gula orden för fler stjärnor"
    const val READING_CONTINUE_WITH_YELLOW_BTN = "Gå vidare"
    const val READING_PRACTICE_WORDS_TITLE = "Ord att öva"
    const val READING_TIME_ROW_PREFIX = "Din tid:"
    const val READING_TIME_ROW_BEST_PREFIX = "Bästa:"
    const val READING_TIMED_CHALLENGE_COMPLETED = "Tidsutmaning klarad!"
    const val READING_TIME_RECORD_AT_MAX_STARS_NOTE =
        "Snabbare tid sparas som personbästa, men ger inga fler stjärnor på en bana med 3 stjärnor."

    // Math & English Activity
    const val MATH_HINT_BTN = "Visa ledtråd"
    const val MATH_GROWTH_MINDSET_BANNER = "Bra försök! Här är en ledtråd som hjälper dig."
    const val MATH_NEXT_PROBLEM_BTN = "Nästa uppgift"
    const val ENGLISH_SECTION_TITLE = "Engelska glosor"
    const val ACTIVITY_FINISH_CELEBRATION_TITLE = "Bana klar!"
    const val ACTIVITY_BACK_TO_MAP_BTN = "Fortsätt"
    const val GIFT_DUPLICATE_NOTE = "Presenten innehöll en dubblett"
    const val CHEST_TAP_TO_OPEN = "Tryck för att öppna!"
    const val CHEST_OPENED = "Flitkista öppnad!"
    const val CHEST_DUPLICATE_NOTE = "Flitkistan innehöll en dubblett"
    const val CHEST_METER_EMPTY = "Flitkistan: tom"
    const val CHEST_METER_LOW = "Flitkistan: lite fylld"
    const val CHEST_METER_HALF = "Flitkistan: halvfull"
    const val CHEST_METER_ALMOST_FULL = "Flitkistan: nästan full"
    const val CHEST_METER_INFO_TITLE = "Flitkistan"
    const val CHEST_METER_INFO = "Spela banor för att fylla Flitkistan. När den är full får du en present!"
    const val ITEM_UNLOCKED_TITLE = "Nytt föremål!"
    const val EQUIP_NOW_BTN = "Ta på nu"
    const val EQUIPPED_CONFIRM = "Påtagen!"
    const val AVATAR_UNLOCKED_TITLE = "Ny avatar!"
    const val SWITCH_AVATAR_NOW_BTN = "Byt nu"

    // Wardrobe
    const val WARDROBE_TITLE = "Garderob"
    const val WARDROBE_SUBTITLE = "Klä din avatar med föremål du låst upp"
    const val WARDROBE_MYSTERY_FOOTER = "Fler föremål låses upp när du spelar äventyret"
    const val WARDROBE_NONE_ITEM = "Ingen"
    const val WARDROBE_TRAITS_COLOR_LABEL = "Färg & stil"
    const val WARDROBE_EQUIPPED_LABEL = "Utrustad"
    const val WARDROBE_CHOOSE_LABEL = "Välj"

    // Trophies
    const val TROPHIES_TITLE = "Troféer"
    const val TROPHIES_SUBTITLE = "Klara utmaningar. Få skatter!"

    // Challenge cards (doc/plans/014)
    const val CHALLENGE_READ_ALOUD_CD = "Läs upp"
    const val CHALLENGE_DONE = "Klart!"
    const val CHALLENGE_DONE_SPOKEN = "Klart! Du har tagit alla troféer här."
    const val NEXT_CHALLENGE_TITLE = "Ny utmaning!"
    const val NEXT_CHALLENGE_OK_BTN = "Okej!"

    // Kompisbyn (avatar map) & friends
    const val AVATAR_MAP_TITLE = "Kompisbyn"
    const val AVATAR_MAP_SUBTITLE = "Du och dina kompisar"
    const val AVATAR_MAP_ME_PREFIX = "Du"
    const val AVATAR_MAP_DRESS_UP_BTN = "Klä om"
    const val AVATAR_MAP_ADD_FRIEND = "Lägg till kompis"
    const val AVATAR_MAP_EMPTY = "Inga kompisar än – lägg till din första!"
    const val FRIEND_SHEET_REMOVE_BTN = "Ta bort kompis"
    const val FRIEND_SHEET_GEAR_TITLE = "Har på sig"
    const val FRIEND_SHEET_NO_GEAR = "Har inga föremål på sig än"
    const val SHEET_CLOSE_BTN = "Stäng"
    const val FRIENDS_ADD_BTN = "Lägg till"
    const val FRIENDS_REMOVE_BTN = "Ta bort"
    const val FRIENDS_ERROR_NOT_FOUND = "Hittade ingen kompis med den koden."
    const val FRIENDS_ERROR_ALREADY = "Ni är redan kompisar!"
    const val FRIENDS_ERROR_NETWORK = "Kunde inte nå internet. Försök igen."
    const val FRIENDS_ERROR_EXPIRED = "Koden har gått ut – be om en ny."
    const val FRIENDS_ERROR_SELF = "Du kan inte lägga till din egen kod."
    const val FRIENDS_ERROR_FULL = "Koden har redan använts max antal gånger."
    const val FRIENDS_ERROR_INVALID_QR = "Det där ser inte ut som en kompiskod."
    const val FRIENDS_OFFLINE_NOTICE = "Kompisar kräver internet"

    // doc/plans/030 §7: add-friend sheet tabs, QR code, countdown, "Nya kompisar" live list.
    const val FRIENDS_TAB_MY_CODE = "Min kompiskod"
    const val FRIENDS_TAB_ADD_FRIEND = "Lägg till kompis"
    const val FRIENDS_CODE_LOADING = "Skapar din kod…"
    const val FRIENDS_STOP_CODE_BTN = "Stoppa koden"
    const val FRIENDS_SHOW_NEW_CODE_BTN = "Visa ny kod"
    const val FRIENDS_NEW_FRIENDS_TITLE = "Nya kompisar"
    const val FRIENDS_SCAN_QR_BTN = "Skanna QR-kod"
    const val FRIENDS_CODE_FIELD_PLACEHOLDER = "XXXX-XXXX"
    const val FRIENDS_SCANNER_UNAVAILABLE = "Skriv koden i stället"
    const val FRIENDS_NAME_STEP_TITLE = "Vad heter din kompis?"
    const val FRIENDS_NAME_STEP_HINT = "Bara du ser det här namnet."
    const val FRIENDS_NAME_SAVE_BTN = "Spara"
    const val FRIENDS_NAME_SKIP_BTN = "Hoppa över"
    const val FRIEND_SHEET_CHANGE_NAME_BTN = "Byt namn"
    const val AVATAR_MAP_NICKNAME_PREFIX = "Ditt kompisnamn:"
    const val AVATAR_MAP_SHUFFLE_NICKNAME_BTN = "Slumpa"
    const val FRIEND_REVEAL_TITLE = "Ny kompis!"

    /** "Koden gäller i 9:42" (doc/plans/030 §7); [remainingSeconds] is clamped to >= 0. */
    fun friendCodeExpiresIn(remainingSeconds: Long): String {
        val clamped = remainingSeconds.coerceAtLeast(0)
        val minutes = clamped / 60
        val seconds = clamped % 60
        return "Koden gäller i $minutes:${seconds.toString().padStart(2, '0')}"
    }

    /** "+2 till" shown while more friends wait to be revealed on the inviter side (doc/plans/030 §7). */
    fun friendRevealQueuedMore(count: Int): String = "+$count till"

    /** "3 nya kompisar!" badge on the village's "+" plot (doc/plans/030 §7). */
    fun friendsUnrevealedBadge(count: Int): String =
        if (count == 1) "1 ny kompis!" else "$count nya kompisar!"

    // Parent View & GDPR
    const val PARENT_GATE_TITLE = "Föräldraläge"
    const val PARENT_GATE_SUBTITLE = "Lös mattetalet nedan för att öppna föräldravyn."
    const val PARENT_GATE_INPUT_LABEL = "Ditt svar"
    const val PARENT_GATE_OPEN_BTN = "Öppna"
    const val PARENT_GATE_WRONG_MSG = "Fel svar, försök igen"
    const val PARENT_DASHBOARD_TITLE = "Föräldraöversikt"
    const val PARENT_DAILY_GOALS_TITLE = "Dagliga mål"
    const val PARENT_READING_GOAL_LABEL = "Läsmål per dag"
    const val PARENT_MATH_GOAL_LABEL = "Mattemål per dag"
    const val PARENT_SKIP_TUTORIAL_TOGGLE = "Tillåt att hoppa över introduktion"
    const val PARENT_STRUGGLED_WORDS_TITLE = "Ord att öva på (sparas enbart lokalt)"
    const val PARENT_STRUGGLED_WORDS_EMPTY = "Inga svåra ord registrerade ännu"
    const val PARENT_MATH_PATTERNS_TITLE = "Matteutmaningar (sparas enbart lokalt)"
    const val PARENT_MATH_PATTERNS_EMPTY = "Inga fel registrerade ännu"
    const val PARENT_FRIENDS_OVERSIGHT_TITLE = "Kompislista"
    const val PARENT_GDPR_TITLE = "Integritet & data (GDPR)"
    const val PARENT_GDPR_PRIVACY_NOTE =
        "Inga röstinspelningar sparas. All data lagras tryggt och lokalt på enheten."
    const val PARENT_LOCAL_ONLY_ACTIVE = "Lokal lagring (100 % offline på enheten)"
    const val PARENT_CLOUD_ACCOUNT_ACTIVE = "Molnkonto för kompisavatar:"
    const val PARENT_EMAIL_INPUT_LABEL = "E-postadress"
    const val PARENT_SEND_EMAIL_LINK_BTN = "Skicka inloggningslänk"
    const val PARENT_EMAIL_LINK_SENT_BANNER = "Inloggningslänk skickad via e-post."
    const val PARENT_EMAIL_LINK_INPUT_LABEL = "E-postlänk"
    const val PARENT_COMPLETE_EMAIL_LINK_BTN = "Slutför inloggning"
    const val PARENT_CONNECT_ACCOUNT_BTN = "Koppla konto"
    const val PARENT_SWITCH_LOCAL_ONLY_BTN = "Växla till lokalt läge"
    const val PARENT_DELETE_ACCOUNT_BTN = "Radera konto och data"
    const val CRASHLYTICS_DIALOG_TITLE = "Hjälp till att förbättra Lixo"
    const val CRASHLYTICS_DIALOG_BODY =
        "Vill du tillåta anonyma kraschrapporter? Ingen personlig data eller röstdata skickas någonsin."
    const val CRASHLYTICS_ACCEPT_BTN = "Tillåt"
    const val CRASHLYTICS_DECLINE_BTN = "Nej tack"

    fun slotName(slot: EquipmentSlot): String = when (slot) {
        EquipmentSlot.HAT -> "Hatt"
        EquipmentSlot.SHOULDERS_BACK -> "Axlar"
        EquipmentSlot.ARMS_GLOVES -> "Armar"
        EquipmentSlot.ARMOR_SHIRT -> "Tröja"
        EquipmentSlot.PANTS -> "Byxor"
        EquipmentSlot.SHOES -> "Skor"
        EquipmentSlot.HAND_RIGHT -> "Höger hand"
        EquipmentSlot.HAND_LEFT -> "Vänster hand"
        EquipmentSlot.TAIL -> "Svans"
    }

    fun rarityName(rarity: Rarity): String = AchievementStrings.rarityNameSv(rarity)

    fun rarityNamePlural(rarity: Rarity): String = when (rarity) {
        Rarity.COMMON -> "Vanliga"
        Rarity.UNCOMMON -> "Ovanliga"
        Rarity.RARE -> "Sällsynta"
        Rarity.EPIC -> "Episka"
        Rarity.LEGENDARY -> "Legendariska"
    }

    /** Collection-count chip label for the wardrobe's rarity row, e.g. "Legendariska 2/38". */
    fun wardrobeRarityCount(rarity: Rarity, owned: Int, total: Int): String =
        "${rarityNamePlural(rarity)} $owned/$total"

    /**
     * Base label for [source] (e.g. "Prestation"), plus [detail] appended as "$base: $detail"
     * when present and non-blank (e.g. "Prestation: Blixtläsaren" for an achievement chest).
     */
    fun rewardSourceLabel(source: RewardSource, detail: String? = null): String {
        val base = when (source) {
            RewardSource.LEVEL -> "Banbelöning"
            RewardSource.STARS -> "Stjärnbelöning"
            RewardSource.FLITKISTA -> "Flitkistan"
            RewardSource.GIFT -> "Present"
            RewardSource.WEEKLY_STREAK -> "Veckans streak"
            RewardSource.ACHIEVEMENT -> "Prestation"
        }
        return if (detail.isNullOrBlank()) base else "$base: $detail"
    }

    fun subjectName(subject: Subject): String = when (subject) {
        Subject.SWEDISH -> "Svenska"
        Subject.MATH -> "Matematik"
        Subject.ENGLISH -> "Engelska"
        Subject.GEOGRAPHY -> "Geografi"
    }

    fun mathTypeName(type: MathProblemType): String = when (type) {
        MathProblemType.ADDITION -> "Addition"
        MathProblemType.SUBTRACTION -> "Subtraktion"
        MathProblemType.TEN_CROSSING -> "Tiotalsövergång"
        MathProblemType.MULTIPLICATION -> "Multiplikation"
        MathProblemType.DIVISION -> "Division"
        MathProblemType.CLOCK -> "Klockan"
        MathProblemType.MONEY -> "Pengar"
        MathProblemType.WORD_PROBLEM -> "Problemlösning"
        MathProblemType.FLAG -> "Flaggor"
    }

    /** Accessibility label for a challenge progress indicator, e.g. "12 av 30". */
    fun challengeProgressA11y(current: Int, target: Int): String = "$current av $target"

    // Events (025)
    const val EVENT_CHEST_DESCRIPTION = "Högtidskista"
    const val EVENT_ITEM_OWNED = "Du har den!"
    const val EVENT_SHEET_SUBTITLE = "Specialföremål som bara finns just nu. Öppna kistor för att hitta dem!"

    // Debug: content preview (020)
    const val CONTENT_PREVIEW_OPEN_BTN = "Debug: innehållspaket"
    const val CONTENT_PREVIEW_TITLE = "Innehållspaket"
    const val CONTENT_PREVIEW_BACK_BTN = "Tillbaka"
    const val CONTENT_PREVIEW_LOADING = "Laddar innehållspaket…"
}
