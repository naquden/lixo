package se.lixo.app.ui.theme

import se.lixo.app.domain.model.AvatarType
import se.lixo.app.domain.model.EquipmentSlot
import se.lixo.app.domain.model.MathProblemType
import se.lixo.app.domain.model.Rarity
import se.lixo.app.domain.model.Subject

/**
 * Strongly-typed Swedish localization catalog mirroring `composeResources/values/strings.xml`.
 * Ensures zero hardcoded UI strings in screen composables (`project.md` §1:
 * "Gränssnittet är enbart svenska i första versionen. All UI-text läggs i språkresurser").
 */
object LixoStrings {
    const val APP_NAME = "Lixo"

    // Bottom Navigation
    const val NAV_MAP = "Karta"
    const val NAV_WARDROBE = "Garderob"
    const val NAV_TROPHIES_FRIENDS = "Troféer & Vänner"
    const val NAV_PARENT_SETTINGS = "Föräldravy"

    // Onboarding & Profile Selection
    const val PROFILE_SELECT_TITLE = "Vem ska ut på äventyr idag?"
    const val PROFILE_TAP_TO_START = "Tryck på din avatar för att börja!"
    const val PROFILE_ADD_SIBLING = "+ Ny syskonprofil"
    const val PROFILE_NEW_NAME_LABEL = "Barnets förnamn eller smeknamn"
    const val PROFILE_CREATE_BTN = "Skapa profil"

    // Tutorial
    const val TUTORIAL_WELCOME_TITLE = "Välkommen till Lixo — Magisk Högläsning!"
    const val TUTORIAL_MIC_PROMPT =
        "Be en förälder godkänna mikrofonen. När du läser sagan högt lyssnar AI-läsassistenten och markerar varje ord i realtid!"
    const val TUTORIAL_ALLOW_MIC_BTN = "Starta med AI-röst (Rekommenderas)"
    const val TUTORIAL_MANUAL_FALLBACK_BTN = "Kör i manuellt läsläge"
    const val TUTORIAL_SKIP_BTN = "Hoppa över tutorial"
    const val TUTORIAL_REWARD_BANNER = "Stjärnregn! Du låste upp din första hatt i garderoben!"
    const val TUTORIAL_CONTINUE_TO_MAP_BTN = "Ut på äventyrskartan!"

    // Campaign Map
    const val MAP_STARS_LABEL = "Stjärnor"
    const val MAP_STREAK_LABEL = "Dagar i rad"
    const val MAP_TIMED_BADGE = "Tidsuppdrag"
    const val MAP_PLAY_LEVEL_BTN = "Spela bana"
    const val MAP_REPLAY_GIFT_HINT = "Repetition ger chans på present!"
    const val MAP_MAXED_STARS_HINT = "Redan 3 stjärnor (Ger present vid AI-repetition)"
    const val MAP_LOCKED_HINT = "Klara föregående bana först!"

    // Reading Activity
    const val READING_MODE_SELECT_TITLE = "Välj läsläge innan du börjar"
    const val READING_MODE_AI_LABEL = "AI-Läsassistent (1–3 stjärnor + gåvor)"
    const val READING_MODE_MANUAL_LABEL = "Manuellt läsläge (Högst 1 stjärna)"
    const val READING_MODE_LOCKED_BADGE = "Läsläge låst under pågående saga"
    const val READING_TIMED_CHALLENGE_TOGGLE = "Tidsuppdrag (Kräver AI-läge)"
    const val READING_SIMULATE_VOICE_BTN = "Läs nästa ord (AI-röst / Demo)"
    const val READING_SIMULATE_FULL_SENTENCE_BTN = "Läs hela meningen i referenstempo"
    const val READING_MANUAL_DONE_BTN = "Jag har läst klart"
    const val READING_MANUAL_CHARGING_LABEL = "Läser i lugn takt..."
    const val READING_STUCK_TITLE = "Vill du ha lite hjälp med ordet?"
    const val READING_HELP_LISTEN = "Lyssna"
    const val READING_HELP_SYLLABLES = "Stavelser"
    const val READING_HELP_EXPLANATION = "Ordförklaring"
    const val READING_HELP_SKIP_NO_PENALTY = "Gå vidare utan avdrag"
    const val READING_TAP_WORD_HINT = "Tips: Tryck på vilket ord som helst för stavelser & ordförklaring!"

    // Math & English Activity
    const val MATH_SUBMIT_BTN = "Svara"
    const val MATH_HINT_BTN = "Visa ledtråd"
    const val MATH_GROWTH_MINDSET_BANNER = "Bra försök! Vi kollar på en visuell ledtråd tillsammans."
    const val MATH_NEXT_PROBLEM_BTN = "Nästa uppgift"
    const val ENGLISH_SECTION_TITLE = "Engelska glosor & enkel lästext"
    const val ACTIVITY_FINISH_CELEBRATION_TITLE = "Uppdrag avklarat!"
    const val ACTIVITY_BACK_TO_MAP_BTN = "Tillbaka till kartan"
    const val GIFT_UNLOCKED_TITLE = "Present öppnad!"
    const val GIFT_DUPLICATE_NOTE = "Presenten innehöll en dubblett — bra repeterat!"

    // Wardrobe
    const val WARDROBE_TITLE = "Universell Garderob"
    const val WARDROBE_SUBTITLE = "Alla upplåsta föremål passar alla avatarer!"
    const val WARDROBE_MYSTERY_FOOTER = "Ej upplåsta föremål är hemliga tills du hittar dem på ditt äventyr."
    const val WARDROBE_UNEQUIP_BTN = "Ta av föremål i vald plats"
    const val WARDROBE_TRAITS_COLOR_LABEL = "Färgtema & Uttryck"

    // Trophies & Friends
    const val TAB_TROPHIES = "Troféer & Prestationer"
    const val TAB_FRIENDS = "Kompisgalleri (QR / Användarnamn)"
    const val TROPHIES_SUBTITLE = "Episka och Legendariska föremål låses upp via unika prestationer!"
    const val FRIENDS_SUBTITLE = "Lägg till kompisar via QR-kod eller användarnamn. Inga riktiga namn, ingen chatt."
    const val FRIENDS_INPUT_PLACEHOLDER = "Skriv användarnamn eller QR-kod (t.ex. magisk_katt_07)"
    const val FRIENDS_ADD_BTN = "Lägg till vän"
    const val FRIENDS_REMOVE_BTN = "Ta bort"
    const val FRIENDS_MY_CODE_PREFIX = "Min kompiskod:"

    // Parent View & GDPR
    const val PARENT_GATE_TITLE = "Föräldravy — Skyddad av svårt mattetal"
    const val PARENT_GATE_SUBTITLE = "Lös uträkningen nedan för att öppna inställningar och statistik."
    const val PARENT_GATE_INPUT_LABEL = "Ditt svar"
    const val PARENT_GATE_OPEN_BTN = "Öppna Föräldravy"
    const val PARENT_GATE_WRONG_MSG = "Fel svar — prova igen!"
    const val PARENT_DASHBOARD_TITLE = "Föräldravy & Pedagogisk Översikt"
    const val PARENT_DAILY_GOALS_TITLE = "Dagliga mål & Tutorial-inställning"
    const val PARENT_READING_GOAL_LABEL = "Dagligt läsmål (minuter)"
    const val PARENT_MATH_GOAL_LABEL = "Dagligt mattemål (tal)"
    const val PARENT_SKIP_TUTORIAL_TOGGLE = "Tillåt att tutorial hoppas över för barnprofil"
    const val PARENT_STRUGGLED_WORDS_TITLE = "Ord som kan behöva övas (Endast lokalt på enheten)"
    const val PARENT_STRUGGLED_WORDS_EMPTY = "Inga svåra ord loggade ännu."
    const val PARENT_MATH_PATTERNS_TITLE = "Matte-mönster & Ledtrådar (Endast lokalt på enheten)"
    const val PARENT_MATH_PATTERNS_EMPTY = "Inga mattefel loggade ännu."
    const val PARENT_FRIENDS_OVERSIGHT_TITLE = "Barnets vänlista"
    const val PARENT_GDPR_TITLE = "Data och integritet (GDPR)"
    const val PARENT_GDPR_PRIVACY_NOTE =
        "Ingen röstlagring sker någonsin. Läshistorik, ord-logg och mattefel sparas enbart lokalt på telefonen och laddas aldrig upp."
    const val PARENT_LOCAL_ONLY_ACTIVE = "Läge: Lokal-only (Inget konto, 100 % offline på enheten)"
    const val PARENT_CLOUD_ACCOUNT_ACTIVE = "Valfritt molnkonto aktivt för publik vän-avatar:"
    const val PARENT_EMAIL_INPUT_LABEL = "Valfri e-post för molnsynk av vän-avatar"
    const val PARENT_SEND_EMAIL_LINK_BTN = "Skicka inloggningslänk via e-post (Lösenordsfri)"
    const val PARENT_EMAIL_LINK_SENT_BANNER = "Inloggningslänk skickad! Öppna länken i mejlet på den här enheten."
    const val PARENT_EMAIL_LINK_INPUT_LABEL = "E-postlänk (fylls i automatiskt vid klick på mejllänk)"
    const val PARENT_COMPLETE_EMAIL_LINK_BTN = "Slutför inloggning med e-postlänk"
    const val PARENT_CONNECT_ACCOUNT_BTN = "Koppla valfritt konto direkt"
    const val PARENT_SWITCH_LOCAL_ONLY_BTN = "Byt till Lokal-only-läge"
    const val PARENT_DELETE_ACCOUNT_BTN = "Radera konto och all molndata"
    const val CRASHLYTICS_DIALOG_TITLE = "Samtycke för kraschrapporter (3:e starten)"
    const val CRASHLYTICS_DIALOG_BODY =
        "Vill du tillåta anonyma tekniska kraschrapporter via Firebase Crashlytics? Inget ljud och ingen barn- eller läxdata ingår någonsin."
    const val CRASHLYTICS_ACCEPT_BTN = "Tillåt kraschrapporter"
    const val CRASHLYTICS_DECLINE_BTN = "Nej tack (Håll avstängt)"

    fun avatarName(type: AvatarType): String = when (type) {
        AvatarType.CAT -> "Katt"
        AvatarType.DOG -> "Hund"
        AvatarType.KNIGHT -> "Riddare"
        AvatarType.PRINCESS -> "Prinsessa"
        AvatarType.WIZARD -> "Trollkarl"
        AvatarType.WITCH -> "Häxa"
    }

    fun slotName(slot: EquipmentSlot): String = when (slot) {
        EquipmentSlot.HAT -> "Hatt"
        EquipmentSlot.SHOULDERS_BACK -> "Axlar & Rygg"
        EquipmentSlot.ARMS_GLOVES -> "Armar & Handskar"
        EquipmentSlot.ARMOR_SHIRT -> "Rustning & Tröja"
        EquipmentSlot.PANTS -> "Byxor"
        EquipmentSlot.SHOES -> "Skor"
        EquipmentSlot.HAND_RIGHT -> "Höger hand"
        EquipmentSlot.HAND_LEFT -> "Vänster hand"
        EquipmentSlot.TAIL -> "Svans"
    }

    fun rarityName(rarity: Rarity): String = when (rarity) {
        Rarity.COMMON -> "Vanlig"
        Rarity.UNCOMMON -> "Ovanlig"
        Rarity.RARE -> "Sällsynt"
        Rarity.EPIC -> "Episk"
        Rarity.LEGENDARY -> "Legendarisk"
    }

    fun subjectName(subject: Subject): String = when (subject) {
        Subject.SWEDISH -> "Svenska"
        Subject.MATH -> "Matematik"
        Subject.ENGLISH -> "Engelska"
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
    }
}
