#!/usr/bin/env python3
"""
Lixo Content & Curriculum Generation Pipeline (project.md §2 & §3).

Generates, validates, and optionally uploads to Cloud Firestore (`lixo-fd45d`)
the curriculum bundle (`seed_campaign_content.json`) containing:
- 7 MVP Courses (`courses/{courseId}`: 3 Swedish story courses, 3 Math courses, 1 English course)
- 100 Linear Campaign Levels (`levels/{levelId}`)
- 100 Homework Tasks (`tasks/{taskId}`) with 25 original Swedish short stories, progressive static
  Math problem sets across all 8 categories (addition, subtraction, ten_crossing, multiplication,
  division, clock, money, word_problem), and English vocabulary/simple reading items
- 108 Universal Wardrobe Items across all 9 equipment slots and 5 Fortnite rarity tiers
- Pre-generated Swedish Offline Dictionary (`dictionary/{wordId}`) with syllable splits
  (e.g. "rid-dar-vagn") and child-friendly explanations
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Tuple


FIREBASE_PROJECT_ID = "lixo-fd45d"
FIRESTORE_DATABASE_ID = "(default)"

EQUIPMENT_SLOTS = [
    ("hat", "socket_hat"),
    ("shoulders_back", "socket_shoulders_back"),
    ("arms_gloves", "socket_arms_gloves"),
    ("armor_shirt", "socket_armor_shirt"),
    ("pants", "socket_pants"),
    ("shoes", "socket_shoes"),
    ("hand_right", "socket_hand_right"),
    ("hand_left", "socket_hand_left"),
    ("tail", "socket_tail"),
]

FORTNITE_RARITIES = [
    ("common", 0xFF9E9E9E),
    ("uncommon", 0xFF2ECC71),
    ("rare", 0xFF3498DB),
    ("epic", 0xFF9B59B6),
    ("legendary", 0xFFF39C12),
]

MATH_PROBLEM_TYPES = [
    "addition",
    "subtraction",
    "ten_crossing",
    "multiplication",
    "division",
    "clock",
    "money",
    "word_problem",
]

SWEDISH_STORIES: List[Tuple[str, str]] = [
    (
        "Lixos Första Skogsstig",
        "Katten Lixo smyger över den gamla träbron i skogen. Under bron porlar en klar bäck med glittrande stenar. På andra sidan väntar en vänlig uggla med ett magiskt äventyr.",
    ),
    (
        "Bagarens Försvunna Nyckel",
        "Riddaren spänner på sig sin blå mantel innan solen går upp. Idag ska han rida på sin riddarvagn genom dalen för att hjälpa byns bagare att hitta den gyllene nyckeln.",
    ),
    (
        "Fjärilarna i Sagotornet",
        "Prinsessan och hunden bygger ett högt torn av kuddar och böcker. När de öppnar sagoboken flyger små lysande fjärilar ut genom fönstret och dansar över gården.",
    ),
    (
        "Den Sjungande Grytan",
        "Trollkarlen blandar blåbär och stjärnstoft i sin runda gryta. Plötsligt börjar sleven dansa av sig själv och sjunger en glad melodi som hörs ända till skogsbrynet.",
    ),
    (
        "Ekorrens Vinterförråd",
        "Häxan flyger lågt över ängen där blommorna doftar sommar. Hon vinkar till den lilla ekorren som samlar kottar och hasselnötter i sitt hemliga förråd i eken.",
    ),
    (
        "Skattkistan under Näckrosen",
        "Vid den lugna skogstjärnen guppar en vit näckros på vattnet. Hunden doppar tassen försiktigt och ser en liten skattkista som glimmar bland rötterna på botten.",
    ),
    (
        "Ugglans Hemliga Karta",
        "Uppe i den högsta granen sitter ugglan och rullar ut ett gammalt pergament. På kartan syns en slingrande stig som leder förbi vattenfallet till Stjärnborgen.",
    ),
    (
        "Kompassen som Pekade Baklänges",
        "Katten Lixo hittar en rund mässingskompass i mossan. Men nålen pekar inte mot norr, utan snurrar tre varv och pekar rakt mot ett ihåligt äppelträd.",
    ),
    (
        "Klocktornets Melodi",
        "Mitt i Sifferdalen står ett gammalt klocktorn av röd sten. Varje gång visarna står på hel timme spelar små mekaniska fåglar en vacker trudelutt för byborna.",
    ),
    (
        "Marknaden i Glosornas Hamn",
        "Nere i hamnen gungar segelbåtarna med färgglada vimplar i vinden. Sjöfarare från fjärran länder byter kryddor, sagoböcker och glittrande snäckor på kajen.",
    ),
    (
        "Drakens Varma Halsduk",
        "Uppe i Kristallbergen sitter en liten grön drake och huttrar i snön. Prinsessan stickar en lång, randig halsduk av mjukaste ull så att draken kan värma sin hals.",
    ),
    (
        "Månfyrens Väktare",
        "På den yttersta klippan vid havet lyser månfyren med ett milt silverljus. Fyrens väktare putsar det stora glaset varje kväll så att skeppen hittar hem i mörkret.",
    ),
    (
        "Kristallgrottans Eko",
        "När riddaren kliver in i kristallgrottan tänds tusen blå och lila pelare. Varje fotsteg ger ifrån sig en klar ton som låter precis som ett litet piano.",
    ),
    (
        "Brevduvan med Guldbrevet",
        "En vit brevduva landar på slottsmuren med ett förseglat kuvert i näbben. I brevet står en inbjudan till årets stora sagofestival i Stjärnborgens borggård.",
    ),
    (
        "Trollskogens Lysande Svampar",
        "När skymningen faller över Viskande Skogen börjar små hattar av mossa och svamp att lysa. De visar vägen hem för alla djur som har dröjt sig kvar.",
    ),
    (
        "Den Flygande Bokhyllan",
        "I trollkarlens bibliotek har böckerna fått egna små vingar av papper. Om man viskar rätt lösenord svävar favoritboken ner direkt i famnen på läsaren.",
    ),
    (
        "Spegelsjöns Gåta",
        "Spegelsjön ligger så blank att molnen ser ut att simma under ytan. För att öppna porten i berget måste Lixo räkna alla sju stjärnbilder som speglas i vattnet.",
    ),
    (
        "Smedens Stjärnsköld",
        "Byns smed arbetar vid sin varma ässja medan gnistorna yr. Av gammalt stjärnmalm smider han en lätt och hållbar sköld prydd med siffror och runor.",
    ),
    (
        "Norrskenets Dans över Borgen",
        "En gnistrande vinterkväll målar norrskenet gröna och violetta band över himlen. Alla vännerna samlas på takterrassen med varm choklad och tittar upp.",
    ),
    (
        "Vindbron över Molnravinen",
        "Mellan två höga bergstoppar hänger en bro flätad av starka rep och ekplankor. Hunden går först med stadiga steg och vinkar glatt när dimman lättar.",
    ),
    (
        "Trädgårdens Pratande Solros",
        "Bakom slottets växthus växer en solros som är nästan lika hög som ett äppelträd. Varje morgon vänder den sitt gula ansikte mot solen och hälsar god morgon.",
    ),
    (
        "Timglasets Sista Sandkorn",
        "På bordet i observatoriet står ett stort timglas fyllt med gyllene sand. Innan det sista sandkornet har runnit ner hinner Lixo läsa hela kapitlet högt.",
    ),
    (
        "Snöleopardens Spår",
        "Högt uppe på glaciären syns runda tassavtryck i den nyfallna snön. Spåren leder till en solig bergshylla där en vänlig snöleopard vilar i lä.",
    ),
    (
        "Kometens Hälsning",
        "Genom teleskopet i det högsta tornet ser häxan en komet med en lång, silvervit svans. Kometen passerar bara vart tionde år och önskar alla läsare lycka till.",
    ),
    (
        "Stjärnborgens Stora Bibliotek",
        "Äntligen öppnas de tunga ekportarna till Stjärnborgens innersta sal. Där väntar tusentals berättelser och kloka gåtor för varje nyfiket barn som älskar att lära.",
    ),
]

ENGLISH_LESSONS: List[Tuple[str, List[Dict[str, str]]]] = [
    (
        "The cat has a blue hat. The dog sees a bright star in the sky.",
        [
            {
                "englishWord": "hat",
                "swedishTranslation": "hatt",
                "simpleSentenceEn": "The cat has a blue hat.",
                "simpleSentenceSv": "Katten har en blå hatt.",
            },
            {
                "englishWord": "star",
                "swedishTranslation": "stjärna",
                "simpleSentenceEn": "I see a bright star.",
                "simpleSentenceSv": "Jag ser en klar stjärna.",
            },
            {
                "englishWord": "dog",
                "swedishTranslation": "hund",
                "simpleSentenceEn": "The friendly dog wags its tail.",
                "simpleSentenceSv": "Den vänliga hunden viftar på svansen.",
            },
        ],
    ),
    (
        "The brave knight lives in a big castle near the green forest.",
        [
            {
                "englishWord": "castle",
                "swedishTranslation": "slott",
                "simpleSentenceEn": "The knight lives in a castle.",
                "simpleSentenceSv": "Riddaren bor i ett slott.",
            },
            {
                "englishWord": "forest",
                "swedishTranslation": "skog",
                "simpleSentenceEn": "The owl flies in the green forest.",
                "simpleSentenceSv": "Ugglan flyger i den gröna skogen.",
            },
            {
                "englishWord": "shield",
                "swedishTranslation": "sköld",
                "simpleSentenceEn": "The knight holds a shiny shield.",
                "simpleSentenceSv": "Riddaren håller en blank sköld.",
            },
        ],
    ),
    (
        "The wizard reads an old book by the warm fire in the tower.",
        [
            {
                "englishWord": "book",
                "swedishTranslation": "bok",
                "simpleSentenceEn": "She opens a magical book.",
                "simpleSentenceSv": "Hon öppnar en magisk bok.",
            },
            {
                "englishWord": "fire",
                "swedishTranslation": "eld",
                "simpleSentenceEn": "The fire is warm and bright.",
                "simpleSentenceSv": "Elden är varm och klar.",
            },
            {
                "englishWord": "tower",
                "swedishTranslation": "torn",
                "simpleSentenceEn": "The princess looks out from the tower.",
                "simpleSentenceSv": "Prinsessan tittar ut från tornet.",
            },
        ],
    ),
    (
        "Five red apples grow on the tree beside the small river.",
        [
            {
                "englishWord": "apple",
                "swedishTranslation": "äpple",
                "simpleSentenceEn": "The horse eats a red apple.",
                "simpleSentenceSv": "Hästen äter ett rött äpple.",
            },
            {
                "englishWord": "tree",
                "swedishTranslation": "träd",
                "simpleSentenceEn": "A squirrel climbs up the tree.",
                "simpleSentenceSv": "En ekorre klättrar upp i trädet.",
            },
            {
                "englishWord": "river",
                "swedishTranslation": "flod",
                "simpleSentenceEn": "The boat sails on the river.",
                "simpleSentenceSv": "Båten seglar på floden.",
            },
        ],
    ),
    (
        "The golden key opens the treasure box under the rainbow.",
        [
            {
                "englishWord": "key",
                "swedishTranslation": "nyckel",
                "simpleSentenceEn": "We found the golden key.",
                "simpleSentenceSv": "Vi hittade den gyllene nyckeln.",
            },
            {
                "englishWord": "treasure",
                "swedishTranslation": "skatt",
                "simpleSentenceEn": "The map leads to a hidden treasure.",
                "simpleSentenceSv": "Kartan leder till en gömd skatt.",
            },
            {
                "englishWord": "rainbow",
                "swedishTranslation": "regnbåge",
                "simpleSentenceEn": "A colorful rainbow shines after the rain.",
                "simpleSentenceSv": "En färgglad regnbåge lyser efter regnet.",
            },
        ],
    ),
]


def build_wardrobe_items() -> List[Dict[str, Any]]:
    items: List[Dict[str, Any]] = []
    slot_subjects = {
        "hat": "english",
        "shoulders_back": "swedish",
        "arms_gloves": "math",
        "armor_shirt": "swedish",
        "pants": "swedish",
        "shoes": "swedish",
        "hand_right": "math",
        "hand_left": "math",
        "tail": "swedish",
    }

    for slot_idx, (slot_id, socket_name) in enumerate(EQUIPMENT_SLOTS):
        for tier_idx in range(12):
            if tier_idx < 4:
                rarity_id, color_hex = FORTNITE_RARITIES[0]
            elif tier_idx < 7:
                rarity_id, color_hex = FORTNITE_RARITIES[1]
            elif tier_idx < 9:
                rarity_id, color_hex = FORTNITE_RARITIES[2]
            elif tier_idx < 11:
                rarity_id, color_hex = FORTNITE_RARITIES[3]
            else:
                rarity_id, color_hex = FORTNITE_RARITIES[4]

            item_id = (
                "item_hat_starter_explorer"
                if (slot_id == "hat" and tier_idx == 0)
                else f"item_{slot_id}_{tier_idx + 1}"
            )
            items.append(
                {
                    "id": item_id,
                    "nameSv": f"{slot_id.replace('_', ' ').title()} #{tier_idx + 1}",
                    "descriptionSv": f"Universellt föremål för platsen {slot_id} ({rarity_id}).",
                    "slot": slot_id.upper(),
                    "rarity": rarity_id.upper(),
                    "subjectTheme": slot_subjects[slot_id].upper(),
                    "meshAssetPath": "3d_sample.glb",
                    "meshNodeName": f"{socket_name}_{tier_idx + 1}",
                    "scaleMultiplier": 1.15 if rarity_id == "legendary" else 1.0,
                    "unlockStarRequirement": 0
                    if item_id == "item_hat_starter_explorer"
                    else (slot_idx * 12 + tier_idx) * 2,
                    "unlockAchievementId": f"ach_{slot_id}_{tier_idx}"
                    if rarity_id in ("epic", "legendary")
                    else None,
                    "isWeeklyStreakSetPiece": (tier_idx == 11),
                    "primaryColorHex": color_hex,
                }
            )
    return items


def build_dictionary() -> List[Dict[str, Any]]:
    raw_entries = [
        (
            "riddarvagn",
            "rid-dar-vagn",
            "En ståtlig vagn som dras av hästar när en riddare åker på uppdrag.",
            "Riddaren lastade sin sköld på sin riddarvagn.",
        ),
        (
            "glittrande",
            "glitt-ran-de",
            "Något som blänker och gnistrar i ljuset, som små stjärnor eller diamanter.",
            "Katten Lixo hittade en glittrande hatt i skogen.",
        ),
        (
            "äventyr",
            "ä-ven-tyr",
            "En spännande resa eller upplevelse där det händer oväntade och roliga saker.",
            "Varje ny saga i Lixo är ett magiskt äventyr.",
        ),
        (
            "träbron",
            "trä-bron",
            "En bro byggd av plankor och trä över en bäck eller å.",
            "Katten smög försiktigt över den gamla träbron.",
        ),
        (
            "stjärnstoft",
            "stjärn-stoft",
            "Magiskt, glittrande pulver som ser ut att komma direkt från natthimlens stjärnor.",
            "Trollkarlen strödde lite stjärnstoft i grytan.",
        ),
        (
            "fjärilar",
            "fjä-ri-lar",
            "Insekter med vackra, färgglada vingar som flyger mellan blommor på sommaren.",
            "Små lysande fjärilar flög ut ur sagoboken.",
        ),
        (
            "sagoboken",
            "sa-go-bo-ken",
            "En bok full av påhittade berättelser, äventyr och magiska bilder.",
            "Prinsessan slog upp första sidan i sagoboken.",
        ),
        (
            "skogsbrynet",
            "skogs-bry-net",
            "Kanten där skogen slutar och en äng eller öppen mark börjar.",
            "Räven stannade vid skogsbrynet och lyssnade.",
        ),
        (
            "hasselnötter",
            "has-sel-nöt-ter",
            "Små runda nötter med hårt skal som ekorrar tycker mycket om.",
            "Ekorren gömde fem hasselnötter i det gamla trädet.",
        ),
        (
            "näckrosen",
            "näck-ro-sen",
            "En vacker vattenväxt med stora runda blad och blommor som flyter på sjön.",
            "En grön groda vilade på bladet bredvid näckrosen.",
        ),
        (
            "skattkista",
            "skatt-kis-ta",
            "En kista av trä och järn där guldmynt, juveler eller hemligheter förvaras.",
            "På botten av tjärnen glimmade en gammal skattkista.",
        ),
        (
            "pergament",
            "per-ga-ment",
            "Ett tjockt och gammaldags papper som förr användes till viktiga kartor och brev.",
            "Ugglan rullade försiktigt ut ett gult pergament.",
        ),
        (
            "vattenfallet",
            "vat-ten-fal-let",
            "En plats där vattnet i en fors eller å faller brant nerför en klippa.",
            "Bruset från vattenfallet hördes långt genom skogen.",
        ),
        (
            "mässingskompass",
            "mäs-sings-kom-pass",
            "Ett vägvisande instrument av guldfärgad metall som visar väderstrecken.",
            "Lixo höll en blank mässingskompass i tassen.",
        ),
        (
            "klocktorn",
            "klock-torn",
            "Ett högt torn med en stor urtavla och klockor som slår varje timme.",
            "Mitt på torget stod byns gamla klocktorn.",
        ),
        (
            "segelbåtarna",
            "se-gel-bå-tar-na",
            "Båtar som drivs framåt av vinden med hjälp av stora tygsegel.",
            "Segelbåtarna gungade lugnt inne i hamnen.",
        ),
        (
            "kristallgrottan",
            "kris-tall-grot-tan",
            "En underjordisk grotta vars väggar är täckta av lysande kristaller.",
            "Ljudet ekade vackert inne i kristallgrottan.",
        ),
        (
            "månfyren",
            "mån-fy-ren",
            "Ett fyrtorn som lyser som månsken för att visa båtar rätt väg på natten.",
            "Månfyren syntes långt ute över det mörka havet.",
        ),
        (
            "brevduva",
            "brev-du-va",
            "En smart fågel som kan hitta hem över långa avstånd med ett litet brev.",
            "En vit brevduva landade mjukt på fönsterblecket.",
        ),
        (
            "sagofestival",
            "sa-go-fes-ti-val",
            "En stor och glad fest där alla samlas för att lyssna på och läsa berättelser.",
            "Hela borgen pyntades inför årets sagofestival.",
        ),
        (
            "skymningen",
            "skym-ning-en",
            "Tiden på kvällen precis efter att solen har gått ner men innan det blir helt mörkt.",
            "I skymningen tändes de första stjärnorna.",
        ),
        (
            "bibliotek",
            "bib-li-o-tek",
            "Ett rum eller hus fyllt med hyllor av böcker som man får låna och läsa.",
            "Trollkarlens bibliotek hade böcker ända upp till taket.",
        ),
        (
            "stjärnbilder",
            "stjärn-bil-der",
            "Mönster av stjärnor på natthimlen som liknar djur eller sagofigurer.",
            "Vi räknade sju olika stjärnbilder i den klara natten.",
        ),
        (
            "norrskenet",
            "norr-ske-net",
            "Ett magiskt grönt och lila ljus som dansar på himlen under kalla vinternätter.",
            "Norrskenet lyste upp snön runt Stjärnborgen.",
        ),
        (
            "observatoriet",
            "ob-ser-va-to-ri-et",
            "En byggnad med stor kikare (teleskop) där man studerar månen, planeter och stjärnor.",
            "Uppe i observatoriet tittade de på kometen.",
        ),
        (
            "teleskopet",
            "te-le-sko-pet",
            "En stark stjärnkikare som gör att saker långt borta i rymden ser nära ut.",
            "Genom teleskopet såg häxan kometens glittrande svans.",
        ),
        (
            "glaciären",
            "gla-ci-ä-ren",
            "Ett väldigt tjockt täcke av gammal is och snö högt uppe i bergen.",
            "Snöleoparden vandrade tyst över glaciären.",
        ),
    ]
    return [
        {
            "wordNormalized": word,
            "displayWord": word,
            "syllables": syllables,
            "explanationSv": explanation,
            "exampleSentenceSv": example,
            "languageIso": "sv-SE",
        }
        for word, syllables, explanation, example in raw_entries
    ]


def build_math_problems_for_level(order: int, difficulty: int) -> List[Dict[str, Any]]:
    # Vary numbers deterministically per level order & difficulty so every level feels fresh
    a = 3 + ((order * 3) % (6 + difficulty * 3))
    b = 4 + ((order * 5) % (5 + difficulty * 2))
    add_ans = a + b

    sub_top = 12 + ((order * 4) % (8 + difficulty * 4))
    sub_take = 4 + (order % min(9, sub_top - 2))
    sub_ans = sub_top - sub_take

    # Ten crossing pairs (e.g. 7+5, 8+6, 9+4, 6+7, 8+5)
    ten_pairs = [(8, 5), (7, 6), (9, 4), (8, 7), (6, 8), (9, 6), (7, 5), (8, 9)]
    tc_a, tc_b = ten_pairs[(order // 2) % len(ten_pairs)]
    tc_need = 10 - tc_a
    tc_rest = tc_b - tc_need
    tc_ans = tc_a + tc_b

    # Multiplication
    mul_a = 2 + (order % (2 + difficulty))
    mul_b = 3 + ((order // 2) % (3 + difficulty))
    mul_ans = mul_a * mul_b

    # Division
    div_groups = 2 + (order % 4)
    div_each = 3 + ((order // 3) % (3 + difficulty))
    div_total = div_groups * div_each

    # Clock times (varied hours & minutes: 00, 15, 30, 45)
    clock_cases = [
        (
            3,
            30,
            "03:30",
            ["03:00", "03:15", "03:30", "06:15"],
            "Timvisaren pekar mellan 3 och 4, och minutvisaren pekar rakt ner på 6. Vad är klockan?",
            "När minutvisaren pekar på 6 har det gått en halv timme (30 minuter).",
            "Halv fyra skrivs digitalt som 03:30.",
        ),
        (
            7,
            15,
            "07:15",
            ["07:00", "07:15", "07:30", "03:35"],
            "Korta visaren är strax efter 7 och långa visaren pekar på 3. Vad är klockan?",
            "När minutvisaren pekar på 3 har det gått en kvart (15 minuter).",
            "Kvart över sju skrivs digitalt som 07:15.",
        ),
        (
            9,
            0,
            "09:00",
            ["08:00", "09:00", "09:30", "12:45"],
            "Timvisaren pekar rakt på 9 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "När minutvisaren pekar på 12 är det hel timme (:00).",
            "Klockan nio skrivs digitalt som 09:00.",
        ),
        (
            4,
            45,
            "04:45",
            ["04:15", "04:30", "04:45", "05:15"],
            "Timvisaren närmar sig 5 och minutvisaren pekar på 9. Vad är klockan?",
            "När minutvisaren pekar på 9 har det gått 45 minuter (kvart i 5).",
            "Kvart i fem efter klockan 4 skrivs digitalt som 04:45.",
        ),
        (
            10,
            30,
            "10:30",
            ["10:00", "10:15", "10:30", "11:30"],
            "Timvisaren är mitt emellan 10 och 11, och minutvisaren står på 6. Vad är klockan?",
            "Minutvisaren på 6 betyder 30 minuter över 10.",
            "Halv elva skrivs digitalt som 10:30.",
        ),
    ]
    c_hour, c_min, c_ans, c_choices, c_prompt, c_h1, c_h2 = clock_cases[
        (order // 2) % len(clock_cases)
    ]

    # Money breakdowns in Swedish kronor
    money_cases = [
        (
            [20, 10, 5],
            "35",
            ["25", "30", "35", "40"],
            "Du har en 20-krona, en 10-krona och en 5-krona. Hur många kronor har du tillsammans?",
            "Börja med 20 kr + 10 kr = 30 kr.",
            "Lägg till 5 kr: 30 + 5 = 35 kr!",
        ),
        (
            [50, 20, 10],
            "80",
            ["60", "70", "80", "90"],
            "I skattkistan ligger en 50-lapp, en 20-lapp och en 10-krona. Hur mycket är det totalt?",
            "Lägg ihop sedlarna först: 50 kr + 20 kr = 70 kr.",
            "Lägg sedan till 10-kronan: 70 + 10 = 80 kr!",
        ),
        (
            [20, 20, 5, 2],
            "47",
            ["42", "45", "47", "52"],
            "Riddaren har två 20-lappar, en 5-krona och en 2-krona. Hur många kronor har han?",
            "Två 20-lappar blir 20 + 20 = 40 kr.",
            "5 kr + 2 kr är 7 kr, och 40 + 7 = 47 kr!",
        ),
        (
            [100, 20, 10, 5],
            "135",
            ["125", "130", "135", "145"],
            "Trollkarlen betalar med en 100-lapp, en 20-lapp, en 10-krona och en 5-krona. Vad blir summan?",
            "100 kr + 20 kr + 10 kr = 130 kr.",
            "Lägg till 5-kronan: 130 + 5 = 135 kr!",
        ),
    ]
    m_coins, m_ans, m_choices, m_prompt, m_h1, m_h2 = money_cases[
        (order // 2) % len(money_cases)
    ]

    # Word problems
    wp_total = 14 + (order % 11)
    wp_give = 5 + (order % 5)
    wp_left = wp_total - wp_give

    return [
        {
            "id": f"mp_{order}_addition",
            "type": "ADDITION",
            "promptSv": f"Vad blir {a} + {b}?",
            "correctAnswer": str(add_ans),
            "choices": [
                str(add_ans - 1),
                str(add_ans),
                str(add_ans + 1),
                str(add_ans + 2),
            ],
            "hintStep1Sv": f"Börja på {a} och räkna {b} steg framåt på tallinjen.",
            "hintStep2Sv": f"Lägg ihop tiotal och ental lugnt: {a} + {b} = {add_ans}.",
            "visualClockHour": None,
            "visualClockMinute": None,
            "visualMoneyBreakdownKr": [],
        },
        {
            "id": f"mp_{order}_subtraction",
            "type": "SUBTRACTION",
            "promptSv": f"Vad blir {sub_top} - {sub_take}?",
            "correctAnswer": str(sub_ans),
            "choices": [
                str(sub_ans - 1),
                str(sub_ans),
                str(sub_ans + 1),
                str(sub_ans + 2),
            ],
            "hintStep1Sv": f"Starta på {sub_top} och backa {sub_take} steg bakåt.",
            "hintStep2Sv": f"{sub_top} minus {sub_take} blir {sub_ans}!",
            "visualClockHour": None,
            "visualClockMinute": None,
            "visualMoneyBreakdownKr": [],
        },
        {
            "id": f"mp_{order}_ten_crossing",
            "type": "TEN_CROSSING",
            "promptSv": f"Tiotalsövergång: Vad blir {tc_a} + {tc_b}?",
            "correctAnswer": str(tc_ans),
            "choices": [
                str(tc_ans - 1),
                str(tc_ans),
                str(tc_ans + 1),
                str(tc_ans + 2),
            ],
            "hintStep1Sv": f"{tc_a} behöver {tc_need} till för att bli ett helt tiotal (10).",
            "hintStep2Sv": f"Dela upp {tc_b} i {tc_need} + {tc_rest}: {tc_a} + {tc_need} = 10, och 10 + {tc_rest} = {tc_ans}!",
            "visualClockHour": None,
            "visualClockMinute": None,
            "visualMoneyBreakdownKr": [],
        },
        {
            "id": f"mp_{order}_multiplication",
            "type": "MULTIPLICATION",
            "promptSv": f"Vad blir {mul_a} × {mul_b}?",
            "correctAnswer": str(mul_ans),
            "choices": [
                str(max(1, mul_ans - mul_a)),
                str(mul_ans),
                str(mul_ans + 2),
                str(mul_ans + mul_b),
            ],
            "hintStep1Sv": f"Tänk {mul_a} grupper med {mul_b} stjärnor i varje grupp.",
            "hintStep2Sv": f"Om du lägger ihop {mul_b} totalt {mul_a} gånger får du {mul_ans}!",
            "visualClockHour": None,
            "visualClockMinute": None,
            "visualMoneyBreakdownKr": [],
        },
        {
            "id": f"mp_{order}_division",
            "type": "DIVISION",
            "promptSv": f"{div_total} guldstjärnor delas lika mellan {div_groups} vänner. Hur många får var och en?",
            "correctAnswer": str(div_each),
            "choices": [
                str(max(1, div_each - 1)),
                str(div_each),
                str(div_each + 1),
                str(div_each + 2),
            ],
            "hintStep1Sv": f"Fördela de {div_total} stjärnorna i {div_groups} lika stora högar.",
            "hintStep2Sv": f"Eftersom {div_groups} × {div_each} = {div_total} får varje vän {div_each} stjärnor.",
            "visualClockHour": None,
            "visualClockMinute": None,
            "visualMoneyBreakdownKr": [],
        },
        {
            "id": f"mp_{order}_clock",
            "type": "CLOCK",
            "promptSv": c_prompt,
            "correctAnswer": c_ans,
            "choices": c_choices,
            "hintStep1Sv": c_h1,
            "hintStep2Sv": c_h2,
            "visualClockHour": c_hour,
            "visualClockMinute": c_min,
            "visualMoneyBreakdownKr": [],
        },
        {
            "id": f"mp_{order}_money",
            "type": "MONEY",
            "promptSv": m_prompt,
            "correctAnswer": m_ans,
            "choices": m_choices,
            "hintStep1Sv": m_h1,
            "hintStep2Sv": m_h2,
            "visualClockHour": None,
            "visualClockMinute": None,
            "visualMoneyBreakdownKr": m_coins,
        },
        {
            "id": f"mp_{order}_word_problem",
            "type": "WORD_PROBLEM",
            "promptSv": f"Katten Lixo har plockat {wp_total} blåbär i skogen och bjuder ugglan på {wp_give} stycken. Hur många blåbär har Lixo kvar?",
            "correctAnswer": str(wp_left),
            "choices": [
                str(wp_left - 1),
                str(wp_left),
                str(wp_left + 1),
                str(wp_left + 2),
            ],
            "hintStep1Sv": f"Vi räknar subtraktion: {wp_total} minus {wp_give}.",
            "hintStep2Sv": f"{wp_total} - {wp_give} = {wp_left} blåbär kvar!",
            "visualClockHour": None,
            "visualClockMinute": None,
            "visualMoneyBreakdownKr": [],
        },
    ]


def generate_seed_bundle() -> Dict[str, Any]:
    wardrobe_items = build_wardrobe_items()
    dictionary = build_dictionary()

    # Version 2 + updated timestamp so app delta-sync pulls updated content from Firestore
    updated_at_ms = 1793558400000

    courses = [
        {
            "id": "course_sv_1",
            "title": "Svenska Sagor: Viskande Skogen",
            "subject": "SWEDISH",
            "minGrade": 1,
            "version": 2,
            "updatedAt": updated_at_ms,
        },
        {
            "id": "course_sv_2",
            "title": "Svenska Sagor: Kristallbergen",
            "subject": "SWEDISH",
            "minGrade": 2,
            "version": 2,
            "updatedAt": updated_at_ms,
        },
        {
            "id": "course_sv_3",
            "title": "Svenska Sagor: Stjärnborgens Krönikor",
            "subject": "SWEDISH",
            "minGrade": 3,
            "version": 2,
            "updatedAt": updated_at_ms,
        },
        {
            "id": "course_math_1",
            "title": "Matteäventyret: Plus, Minus & Tiokompisar",
            "subject": "MATH",
            "minGrade": 1,
            "version": 2,
            "updatedAt": updated_at_ms,
        },
        {
            "id": "course_math_2",
            "title": "Klocktornet & Guldbörsen: Tid och Pengar",
            "subject": "MATH",
            "minGrade": 1,
            "version": 2,
            "updatedAt": updated_at_ms,
        },
        {
            "id": "course_math_3",
            "title": "Sifferdalens Gånger, Delat & Kluringar",
            "subject": "MATH",
            "minGrade": 2,
            "version": 2,
            "updatedAt": updated_at_ms,
        },
        {
            "id": "course_en_1",
            "title": "Engelska Glosresan",
            "subject": "ENGLISH",
            "minGrade": 2,
            "version": 2,
            "updatedAt": updated_at_ms,
        },
    ]

    biomes = [
        "Viskande Skogen",
        "Sifferdalen",
        "Glosornas Hamn",
        "Kristallbergen",
        "Stjärnborgen",
    ]

    levels: List[Dict[str, Any]] = []
    tasks: List[Dict[str, Any]] = []

    ref_wpm_by_diff = {1: 40, 2: 55, 3: 70, 4: 85, 5: 100, 6: 115}

    swedish_idx = 0
    math_idx = 0
    english_idx = 0

    for order in range(1, 101):
        biome = biomes[(order - 1) // 20]
        difficulty = min(6, max(1, ((order - 1) // 17) + 1))

        if order % 5 == 0:
            subject = "ENGLISH"
            course_id = "course_en_1"
            task_type = "ENGLISH_VOCAB"
            level_title = f"Bana {order}: Glosäventyr i {biome}"
        elif order % 2 == 0:
            subject = "MATH"
            task_type = "MATH"
            if order <= 34:
                course_id = "course_math_1"
                math_theme = "Tiokompisar & Tallinjen"
            elif order <= 68:
                course_id = "course_math_2"
                math_theme = "Klockan & Guldkronor"
            else:
                course_id = "course_math_3"
                math_theme = "Gånger, Delat & Gåtor"
            level_title = f"Bana {order}: {math_theme} ({biome})"
        else:
            subject = "SWEDISH"
            task_type = "READING"
            if order <= 33:
                course_id = "course_sv_1"
            elif order <= 67:
                course_id = "course_sv_2"
            else:
                course_id = "course_sv_3"
            story_title, _ = SWEDISH_STORIES[swedish_idx % len(SWEDISH_STORIES)]
            level_title = f"Bana {order}: {story_title}"

        level_id = f"level_{order}"
        task_id = f"task_{order}"
        reward_item = wardrobe_items[order % len(wardrobe_items)]["id"]

        levels.append(
            {
                "id": level_id,
                "courseId": course_id,
                "order": order,
                "title": level_title,
                "biomeNameSv": biome,
                "subject": subject,
                "difficulty": difficulty,
                "timeRequirementSeconds": 45 + difficulty * 10,
                "referenceWpm": ref_wpm_by_diff[difficulty],
                "taskId": task_id,
            }
        )

        if task_type == "READING":
            _, story_text = SWEDISH_STORIES[swedish_idx % len(SWEDISH_STORIES)]
            swedish_idx += 1
            tasks.append(
                {
                    "id": task_id,
                    "levelId": level_id,
                    "type": "READING",
                    "subject": "SWEDISH",
                    "textContent": story_text,
                    "mathProblems": [],
                    "englishItems": [],
                    "rewardItemId": reward_item,
                }
            )
        elif task_type == "MATH":
            math_idx += 1
            tasks.append(
                {
                    "id": task_id,
                    "levelId": level_id,
                    "type": "MATH",
                    "subject": "MATH",
                    "textContent": None,
                    "mathProblems": build_math_problems_for_level(order, difficulty),
                    "englishItems": [],
                    "rewardItemId": reward_item,
                }
            )
        else:
            en_text, en_words = ENGLISH_LESSONS[english_idx % len(ENGLISH_LESSONS)]
            english_idx += 1
            tasks.append(
                {
                    "id": task_id,
                    "levelId": level_id,
                    "type": "ENGLISH_VOCAB",
                    "subject": "ENGLISH",
                    "textContent": en_text,
                    "mathProblems": [],
                    "englishItems": [
                        {
                            "id": f"en_{order}_{i + 1}",
                            "englishWord": item["englishWord"],
                            "swedishTranslation": item["swedishTranslation"],
                            "simpleSentenceEn": item["simpleSentenceEn"],
                            "simpleSentenceSv": item["simpleSentenceSv"],
                        }
                        for i, item in enumerate(en_words)
                    ],
                    "rewardItemId": reward_item,
                }
            )

    achievements = [
        {
            "id": "ach_speed_reader_5",
            "titleSv": "Blixtläsaren",
            "descriptionSv": "Klara 5 sagor i AI-läge med 3 stjärnor.",
            "rarity": "EPIC",
            "requiresAiMode": True,
            "targetCount": 5,
            "currentCount": 0,
            "isUnlocked": False,
            "rewardItemId": "item_shoulders_back_10",
        },
        {
            "id": "ach_pronunciation_master_10",
            "titleSv": "Kristallklart Uttal",
            "descriptionSv": "Läs 10 sagor i AI-läge utan att hoppa över något ord.",
            "rarity": "LEGENDARY",
            "requiresAiMode": True,
            "targetCount": 10,
            "currentCount": 0,
            "isUnlocked": False,
            "rewardItemId": "item_armor_shirt_12",
        },
    ]

    return {
        "version": 2,
        "generatedAtIso": "2026-11-01T18:55:00Z",
        "courses": courses,
        "levels": levels,
        "tasks": tasks,
        "wardrobeItems": wardrobe_items,
        "dictionary": dictionary,
        "achievements": achievements,
    }


def validate_seed_bundle(bundle: Dict[str, Any]) -> List[str]:
    errors: List[str] = []

    levels = bundle.get("levels", [])
    tasks = bundle.get("tasks", [])
    items = bundle.get("wardrobeItems", [])
    dictionary = bundle.get("dictionary", [])

    if len(levels) != 100:
        errors.append(f"Expected 100 campaign levels, found {len(levels)}")

    orders = [lvl["order"] for lvl in levels]
    if orders != list(range(1, 101)):
        errors.append("Campaign levels must be strictly ordered 1..100 (linear campaign)")

    if len(tasks) != 100:
        errors.append(f"Expected 100 tasks, found {len(tasks)}")

    if len(items) < 100:
        errors.append(f"Expected 100+ wardrobe items for MVP, found {len(items)}")

    slots_present = {it["slot"].lower() for it in items}
    expected_slots = {s[0] for s in EQUIPMENT_SLOTS}
    if slots_present != expected_slots:
        errors.append(f"Missing equipment slots: {expected_slots - slots_present}")

    rarities_present = {it["rarity"].lower() for it in items}
    expected_rarities = {r[0] for r in FORTNITE_RARITIES}
    if rarities_present != expected_rarities:
        errors.append(f"Missing rarities: {expected_rarities - rarities_present}")

    # Verify syllable splitting in dictionary ("rid-dar-vagn")
    riddarvagn = next((d for d in dictionary if d["wordNormalized"] == "riddarvagn"), None)
    if not riddarvagn or riddarvagn.get("syllables") != "rid-dar-vagn":
        errors.append("Dictionary must include 'riddarvagn' with syllable split 'rid-dar-vagn'")

    # Verify all 8 math problem types exist in static math tasks
    math_types_seen = set()
    for t in tasks:
        for mp in t.get("mathProblems", []):
            math_types_seen.add(mp["type"].lower())
    missing_math = set(MATH_PROBLEM_TYPES) - math_types_seen
    if missing_math:
        errors.append(f"Missing math problem types: {missing_math}")

    return errors


def _to_firestore_value(val: Any) -> Dict[str, Any]:
    """Converts a Python value into a Firestore REST API Value object."""
    if val is None:
        return {"nullValue": None}
    if isinstance(val, bool):
        return {"booleanValue": val}
    if isinstance(val, int):
        return {"integerValue": str(val)}
    if isinstance(val, float):
        return {"doubleValue": val}
    if isinstance(val, str):
        return {"stringValue": val}
    if isinstance(val, list):
        return {"arrayValue": {"values": [_to_firestore_value(x) for x in val]}}
    if isinstance(val, dict):
        return {
            "mapValue": {
                "fields": {k: _to_firestore_value(v) for k, v in val.items()}
            }
        }
    raise TypeError(f"Unsupported Firestore value type: {type(val)}")


def _load_firebase_cli_access_token() -> str:
    cfg_path = Path.home() / ".config" / "configstore" / "firebase-tools.json"
    if not cfg_path.exists():
        raise RuntimeError(
            f"Firebase CLI config not found at {cfg_path}. Run `firebase login` first."
        )
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    token = cfg.get("tokens", {}).get("access_token")
    if not token:
        raise RuntimeError("No access_token found in firebase-tools.json.")
    return token


def upload_bundle_to_firestore(
    bundle: Dict[str, Any],
    project_id: str = FIREBASE_PROJECT_ID,
    database_id: str = FIRESTORE_DATABASE_ID,
) -> Dict[str, int]:
    """
    Uploads courses, levels, tasks, and dictionary documents to Cloud Firestore
    using atomic batch commits (`documents:commit`).
    """
    token = _load_firebase_cli_access_token()
    doc_prefix = f"projects/{project_id}/databases/{database_id}/documents"
    commit_url = f"https://firestore.googleapis.com/v1/{doc_prefix}:commit"

    writes: List[Dict[str, Any]] = []

    for course in bundle.get("courses", []):
        doc_id = course["id"]
        writes.append(
            {
                "update": {
                    "name": f"{doc_prefix}/courses/{doc_id}",
                    "fields": {k: _to_firestore_value(v) for k, v in course.items()},
                }
            }
        )

    for level in bundle.get("levels", []):
        doc_id = level["id"]
        writes.append(
            {
                "update": {
                    "name": f"{doc_prefix}/levels/{doc_id}",
                    "fields": {k: _to_firestore_value(v) for k, v in level.items()},
                }
            }
        )

    for task in bundle.get("tasks", []):
        doc_id = task["id"]
        writes.append(
            {
                "update": {
                    "name": f"{doc_prefix}/tasks/{doc_id}",
                    "fields": {k: _to_firestore_value(v) for k, v in task.items()},
                }
            }
        )

    for entry in bundle.get("dictionary", []):
        doc_id = entry["wordNormalized"]
        writes.append(
            {
                "update": {
                    "name": f"{doc_prefix}/dictionary/{doc_id}",
                    "fields": {k: _to_firestore_value(v) for k, v in entry.items()},
                }
            }
        )

    # Firestore batch commit supports up to 500 writes per request
    batch_size = 400
    for start in range(0, len(writes), batch_size):
        chunk = writes[start : start + batch_size]
        payload = json.dumps({"writes": chunk}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            commit_url,
            data=payload,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json; charset=utf-8",
            },
            method="POST",
        )
        with urllib.request.urlopen(req) as resp:
            if resp.status not in (200, 201):
                raise RuntimeError(f"Firestore commit failed with HTTP {resp.status}")

    return {
        "courses": len(bundle.get("courses", [])),
        "levels": len(bundle.get("levels", [])),
        "tasks": len(bundle.get("tasks", [])),
        "dictionary": len(bundle.get("dictionary", [])),
        "totalWrites": len(writes),
    }


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate and validate Lixo seed content.")
    parser.add_argument(
        "--output",
        default="composeApp/src/commonMain/composeResources/files/seed_campaign_content.json",
        help="Path to write seed_campaign_content.json",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="Validate the generated bundle against all Lixo MVP specification rules.",
    )
    parser.add_argument(
        "--upload-firestore",
        action="store_true",
        help="Upload courses, levels, tasks, and dictionary to Cloud Firestore.",
    )
    args = parser.parse_args(argv)

    repo_root = Path(__file__).resolve().parents[2]
    out_path = repo_root / args.output
    out_path.parent.mkdir(parents=True, exist_ok=True)

    bundle = generate_seed_bundle()
    out_path.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")

    errors = validate_seed_bundle(bundle)
    if errors:
        for err in errors:
            print(f"[ERROR] {err}", file=sys.stderr)
        return 1

    if args.validate:
        print(
            f"[OK] Validated Lixo seed bundle at {out_path}: "
            f"{len(bundle['courses'])} courses, {len(bundle['levels'])} levels, "
            f"{len(bundle['tasks'])} tasks, {len(bundle['wardrobeItems'])} items, "
            f"{len(bundle['dictionary'])} dictionary words."
        )

    if args.upload_firestore:
        try:
            counts = upload_bundle_to_firestore(bundle)
            print(
                f"[OK] Uploaded curriculum to Firestore ({FIREBASE_PROJECT_ID}/{FIRESTORE_DATABASE_ID}): "
                f"{counts['courses']} courses, {counts['levels']} levels, "
                f"{counts['tasks']} tasks, {counts['dictionary']} dictionary words "
                f"({counts['totalWrites']} total documents)."
            )
        except urllib.error.HTTPError as exc:
            err_body = exc.read().decode("utf-8", errors="replace")
            print(f"[ERROR] Firestore upload failed ({exc.code}): {err_body}", file=sys.stderr)
            return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
