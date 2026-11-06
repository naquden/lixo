# **Lixo — Design- & kravspecifikation (Latest)**

Strukturerad kravspecifikation och konceptuell sammanställning för en engagerande, pedagogisk och trygg läxplattform. Alla tidigare öppna beslutspunkter är inarbetade direkt i specifikationen.

---

# **1. Sammanfattning & Vision för Lixo**

Syftet med Lixo är att revolutionera hur barn upplever och genomför sitt skolarbete i hemmet. Genom att integrera beprövade mekaniker från spelvärlden (gamification) omvandlas det traditionella läxarbetet – såsom daglig läsning och repetitionsuppgifter i matematik – till ett interaktivt och lustfyllt äventyr.

Visionen är att skapa ett självständigt driv hos eleven. När barnet läser ett visst antal minuter eller löser matematiska problem ackumuleras framsteg som direkt speglas i deras digitala alter ego. På så vis skapas en positiv återkopplingsloop där flit och kontinuitet belönas med kosmetiska tillbehör, utrustning och visuell status snarare än stress.

**Central USP:** Lixos unika säljargument är en integrerad, AI-driven taligenkänning som aktivt lyssnar när barnet läser högt och markerar varje ord i realtid. Detta ger omedelbar visuell förstärkning och förvandlar den traditionella läsläxan till en interaktiv upplevelse.

## **Plattformar, MVP & språk**

- **Plattformar:** Android + iOS från första versionen (Kotlin/Compose Multiplatform). Om en funktion inte är tillgänglig på en plattform (t.ex. TTS på iOS) **inaktiveras den funktionen på den plattformen** (graceful degradation via `expect/actual` + feature flags) i stället för att blockera releasen. Prestandamål utgår från medium tier-mobiler (se avsnitt Icke-funktionella krav).
- **MVP-scope:** Läsning (svenska) + matte + **engelska** (enkla glosor och simpla lästexter), avatar & garderob, föräldravy, presenter/gåvor. **Skjuts upp:** klass/skola/lärarroll, utvecklingssamtals-PDF, geografi, bild & skapande.
- **Språk:** Gränssnittet är **enbart svenska** i första versionen. All UI-text läggs i språkresurser (inga hårdkodade strängar) för framtida översättning. Engelska som ämne har eget innehåll (glosor/lästext) men appen styrs på svenska.
- **Finansiering & drift:** Appen är 100 % gratis och reklamfri. Utvecklaren betalar Firebase (låg kostnad). App Store-/Play-konton och eventuell juridisk person bestäms vid publicering.

---

# **2. Kärnfunktionalitet & Gamification**

Spelifieringen bygger på ett tydligt orsak-verkan-samband där akademiska prestationer ger direkta kosmetiska upplåsningar.

## **Onboarding & Första intrycket**

- **Interaktiv introduktion & Noll-läge:** Ingenting är upplåst första gången; spelaren börjar med en ren bas-avatar utan extra tillbehör.
- **Tutorial:** Tutorialen är **en högläsningsläxa där AI-läsassistenten lyssnar** och markerar ord i realtid (USP visas direkt). Onboardingen bör be om mikrofonåtkomst tidigt (via föräldern). Om mikrofonen nekas eller AI-lyssningen inte fungerar klaras läxan i **manuellt läsläge**. Exakt lästext, första upplåsta föremål, om tutorialen kan hoppas över och om den visas per barnprofil bestäms vid innehållsproduktion.

## **Avatarsystem & Anpassning**

- **Karaktärsbas & Skapande:** Spelaren kan justera grundläggande drag och uttryck hos sin avatar.
- **Garderob:** Dedikerad modul för dräkter, rustningar, mantlar, huvudbonader och tillbehör.
- **Universal Garderob:** Alla upplåsta föremål ligger i en **gemensam pott** och kan användas av vilken avatar som helst. **Inga avatar-knutna föremål.** Ej upplåsta föremål visas inte (mysterium).
- **Avatartyper (MVP, 6 st):** katt, hund, riddare, prinsessa, trollkarl, häxa. Endast en (eller få) tillgänglig från start; övriga låses upp via uppdrag eller högtidsspecial. Vilken som är startavatar och exakta grunddrag bestäms vid produktion.
- **Utrustningsplatser (9 slots):** hatt, axlar/rygg, armar/handskar, rustning/tröja, byxor, skor, handhållet höger, handhållet vänster, svans. Ansikte, aura/partiklar och skin skjuts upp. Regler för flera föremål per slot och kroppsformsanpassning (katt/hund vs människoform) bestäms vid 3D-pipelinen.

## **Progression & Kampanjstruktur**

- **Linjär kampanj:** Banorna måste klaras i ordning. Egna sagor med stigande svårighetsgrad (svårare ord/meningsbyggnad). MVP: ca **100 banor/sagor**. Omspel av redan maxade banor ger inte fler stjärnor — barnet drivs vidare till nya banor.
- **Stjärnor (inget XP):** Progression mäts i **1–3 stjärnor** per saga (inspirerat av Super Mario 64). Stjärnor låser upp avatarer och specifika föremål. Exakt stjärnkrav per upplåsning balanseras under utvecklingen.
- **Poängsättning läsning:**
  - **3 stjärnor:** AI-läge i minst referenshastighet (full pott ≈ TTS-uppläsningstempo).
  - **2 stjärnor:** AI-läge i minst 0,5 × referenshastighet.
  - **1 stjärna:** Klarad läsning oavsett tid.
  - **Manuellt läsläge:** högst 1 stjärna.
  - **Gula ord sänker stjärnorna (AI-läge):** lästa gula ord väger 1, olästa gula ord väger 2. Viktad andel av sagans ord ≥ 10 % ger −1 stjärna, ≥ 25 % ger −2 stjärnor, men aldrig under 1 stjärna. Når barnet slutet med olästa gula ord kan det läsa dem för fler stjärnor eller trycka **Gå vidare**.
  - Referens-WPM per nivåspann sätts utifrån TTS/barnläshastighet och justeras efter test. Stjärnkrav för matte bestäms vid matteproduktion.
- **Epic/Legendary via prestationer:** Speciella föremål endast via unika prestationer (t.ex. hastighet på X sagor, perfekt uttal på X sagor). Uttalsprestationer kräver AI-läge.
- **Sällsynthet:** Bakgrundsfärg i 2D enligt **Fortnites färgskala** (grå/grön/blå/lila/guld-orange). Legendary har coolast animationer/effekter i 3D. MVP: ca **100+ items** över slots och sällsyntheter.
- **Presenter / gåvor (inte lootbox):** Extra repetition ger en present med ett föremål. Dubblett → ingenting händer. Obegränsat antal gåvor per dag. Helt gratis och reklamfritt.
- **Flitkista:** 1 p per omgång som ger nya stjärnor eller klarar en ny bana, 0,5 p per omspel utan nya stjärnor. Vid 10 p får barnet en kista med ett slumpat föremål (samma dubblett-regel som presenter). Potten exkluderar streak- och prestationsföremål, och poängräknaren delas över alla ämnen.
- **Belöningskurva:** Snabb start med tidiga upplåsningar, sedan gradvis svårare och mer tidskrävande för sällsynta föremål.

## **AI-Läsassistent & Realtime Highlighting (Kärn-USP)**

- **AI-taligenkänning i realtid** med ord-highlighting och vänlig stöttning.
- **Forced Alignment:** Ljud matchas mot känd textsekvens (inte fri STT-gissning).
- **On-device STT är standard och förstahandsval** (integritet, offline, latens). Alternativ (Vosk, KBLab wav2vec2, Whisper.cpp, plattforms-STT m.fl.) undersöks och rangordnas efter preferens av utvecklaren — inte en blockerande kravpunkt. Latensmål verifieras i samma tester.
- **Moln-STT** övervägs endast om prototyptest visar **minst 10 procentenheter högre träffsäkerhet** *och* kortare fördröjning på samma barnröstsfall. Då krävs EU-hosting, biträdesavtal, samtycke och omformulerad policy ("strömmas, lagras aldrig"). Lokal motor eller manuellt läge som reserv offline. Om lokalt väljs stryks WebSockets/gRPC ur arkitekturen.
- **Ingen fonem-/uttalsdata:** Fokus på att rätt ord sägs. STT kan vara flaky — det accepteras.
- **Ingen hård underkänning:** Vid osäkert uttal ges positivt förslag (prova igen, lyssna, se stavelser); barnet kan alltid gå vidare utan avdrag. Fall loggas till föräldravyn som "ord som kan behöva övas". Trösklar för konfidens/"fastnat" bestäms efter prototyp.
- **Ordförklaringar:** Förgenereras med AI (t.ex. Claude) vid innehållsproduktion, lagras i Firebase, laddas ner till lokal Room/SQLite (~1–2 MB). Offline-uppslag utan live-API.
- **Stavelsedelning:** Ingår som fält i samma statiska JSON-ordlista (t.ex. rid-dar-vagn).
- **TTS:** Appen **måste ha svensk röst/tal** (högtalarikoner för instruktioner, UI och ordförklaringar).
- **Matematik-stöttning:** Mild feedback, visuella nedbrytningar och hintar (growth mindset). Uppgiftstyper: addition, subtraktion, tiotalsövergång, multiplikation, division, **klockan**, **pengar**, problemlösning. Inmatning/hint-detaljer bestäms vid UI-bygge.

### **Manuellt läsläge**

Reserv när AI-lyssning inte räcker (nekad mikrofon, bakgrundsljud, flaky STT, svag enhet). Barnet läser själv och trycker **"Jag har läst klart"**.

| | |
| :--- | :--- |
| **Ger** | Kampanjframsteg, dagligt mål, streak |
| **Reducerat** | Högst 1 stjärna |
| **Ger inte** | Present, uttalsprestationer, ord-logg, tidsuppdrag |
| **Minsta tid** | Knappen inaktiv tills rimlig lästid passerat (baserat på textlängd); synlig "laddning" |
| **Lägesval** | Väljs vid start; byte mitt i tillåts inte |
| **Förälder** | Ingen bekräftelse krävs |

## **Tidsbaserade Matte- & Läsutmaningar (Tidsuppdrag)**

- Läsning och matte i satta tidsintervall. **Tidsuppdrag i läsning kräver AI-läge** (manuellt läge går inte).
- **Tidräkning:** Tiden börjar när sagan visas på skärmen. Ingen komplex pauslogik för bakgrund — fokus förväntas.
- Genomförda tidsuppdrag ger särskilda kosmetiska belöningar.

## **Streak**

- Per kalenderdag **kl. 00–24** lokal tid.
- Tills vidare: en streak-dag = **ett avklarat uppdrag per kategori** (t.ex. en läsning och ett matteuppdrag).
- Streak freeze / helger / lov kan läggas till senare. Positiv förstärkning — inga hårda straff vid avbrott.

## **Ämnen & kosmetiska belöningar**

**MVP-ämnen:** Svenska, Matematik, Engelska (glosor + mycket simpel lästext). **Geografi** och **Bild & skapande** skjuts upp (kan återkomma som framtida teman).

| Ämnesområde | Avatardel / Kategori | Exempel | Syfte |
| :--- | :--- | :--- | :--- |
| **Matematik** | Handhållna föremål, redskap & sköldar | Svärd, stavar, sköldar | Problemlösning & logik |
| **Svenska** | Rustning, dräkter & kläder | Mantlar, rustningar | Uthållighet & textförståelse |
| **Engelska** | Huvudbonader, masker & hjälmar | Hattar, kronor, masker | Ordförråd & självförtroende |
| **Vecko-streaks** | Exklusiva helset | Legendariska set, animationer | Vardagsrutin |

## **Sociala funktioner**

- **Hemmabruk, ingen skola:** MVP är **inte** kopplat till läroplan, skolplattform eller fysisk klass. Ingen lärarroll/klasskonton.
- **Kompisgalleri:** Vänner via **QR-kod eller användarnamn** (inga riktiga namn). Lägg till/ta bort simpelt. Synligt: varandras avatar. Inga chattar eller DM.
- **Klassutmaningar:** Utgår. Om utmaningar återkommer senare mäts de i **antal lästa ord**, inte sidor.

---

# **3. Innehåll & Teknisk Arkitektur**

## **Innehåll**

- Allt innehåll **genereras av AI** till appen. Mängd/nivåer bestäms när det känns tillräckligt.
- **Mattetal är statiska** (fasta listor/JSON tillsammans med övrigt innehåll).
- Sagor och ordbok lagras i Firebase och syncas ner till enheten.

## **Offline-first & datamodell**

- Läxor, progression och avatar fungerar offline; sync när nätverk finns.
- **Lokal databas:** **Room** med repository-mönster (cache + synk mot server).
- **Backend SDK:** **GitLive** (Firebase-kotlin-sdk) — beprövat i KMP.
- **Firestore-struktur (innehåll):**
  - `courses/{courseId}` — title, subject, minGrade, version, updatedAt
  - `levels/{levelId}` — order, title, difficulty, timeRequirementSeconds
  - `tasks/{taskId}` — type (`reading` \| `math`), textContent, mathProblems, rewardItemId
- Delta-uppdatering via versionscheck; endast ändrade nivåer hämtas.

## **Konton, synk & säkerhet**

- **Lokal-only-läge obligatoriskt som alternativ:** Inget konto, allt stannar på telefonen (kan gå förlorat).
- **Valfritt konto** via e-post (Firebase) för molnfunktioner (t.ex. att vara sökbar som vän).
- **Vänner:** Avatarer är publika — konto behövs inte för att *lägga till* vänner, men man kan inte *bli tillagd* utan molnnärvaro.
- **Användardata laddas aldrig upp** (framsteg, ord-logg m.m. endast lokalt). Se GDPR-avsnittet.
- **När molnsynk används** (t.ex. publik vänprofil): best practice merge — inventarie = union, stjärnor = bästa resultat, inställningar = last-write-wins.
- **Serverlogik:** Håll det simpelt (barnapp, inget handelsvärde) — **lita på klienten**. Inga FCM-push. Kontoradering enkelt från appen. Firestore Security Rules: användare läser/skriver endast egen data.
- **Bonusgåvor från förälder:** Utgår helt.

---

# **4. Grafik, 3D, UI & Icke-funktionella krav**

## **Visuell stil & 3D**

- **Stil:** 3D Low-Poly inspirerad av Roblox och Lost Castle.
- **Pipeline:** Prompt/koncept via Claude Opus; 3D-generering lokalt (RTX 4090 / open source-modeller / Blender). AI skapar ett **Python-verktyg** för att generera modeller, applicera skelett, rigga och städa.
- **Rendering:** **SceneView** (etablerat, KMP-stöd) med iOS-stöd via samma stack. WebView + model-viewer som eventuell fallback.
- **Modulärt system:** Separata .glb-meshar på skelett-ankarpunkter.
- **Animationer (MVP):** En **idle-animation** via procedurell rigid-body-animation per kroppsdel. Fler animationer (jubel, hopp) kan tillkomma senare. **Presentöppning (skakande kista) ingår i MVP** — se Känsla & feedback.
- **Feedback:** Stjärnregn, partiklar, mikroanimationer på knappar och framstegsmätare.
- **Dummy-grafik** under utveckling; byts ut utan att ändra kodstruktur.

## **Känsla & feedback (kärnprincip)**

Barnet ska *känna* att det lyckas – varje vinst är högljudd, visuell, fysisk och omedelbar.

- Varje tryck ger feedback inom 100 ms (tryckanimation + haptiskt tick).
- Inga tysta upplåsningar – varje belöning får ett eget iscensatt ögonblick.
- Bana klar: stjärnorna poppar in en i taget (fjädring + haptik per stjärna), '+N stjärnor' räknas upp, konfetti exploderar.
- Nytt föremål: en **skakande kista** väntar på tryck; vid öppning flyger locket av, föremålet skjuts ut med ljusstrålar i raritetsfärg + konfetti + kraftig haptik, därefter dialogen **'Ta på nu' / 'Stäng'**.
- Ny avatar: dialog **'Byt nu' / 'Stäng'**.
- Raritet skalar firandet: Legendarisk ger mer konfetti, längre strålar och gyllene glöd.
- Rätt svar: bekräftande haptik + liten studs; fel svar: mjuk avvisande haptik, aldrig straffande.
- Ljud: krokar (`CelebrationSfx`) anropas på samma ställen som haptiken; ljudfiler läggs till senare.
- Implementation: `ui/components/celebration/`, ritat i Compose/Canvas utan extra bibliotek eller bildfiler.

## **Skärmar, flöden & design**

Mål: genialiskt, användarvänligt, snyggt, mycket animationer, ikondrivet, få klick (best practice för barn-UX).

1. **Start & Profilval** — tryck på stor animerad avatar.
2. **Huvudskärm (kampanjkarta)** — scrollbar karta (Candy Crush/Mario-känsla); avatar står på aktuell bana.
3. **Bottom nav** — Karta (Hem), Garderob, Troféer & Vänner; stora knappar med studs.
4. **Föräldravy** — via Settings, skyddad av **svårt mattetal**.
5. **Aktivitetsvyer** — helskärm matte/läsning; stäng-kryss; framstegsmätare med partiklar.

**Design:** Tactile knappar, mikroanimationer, dyslexivänligt typsnitt (t.ex. Lexend), TTS-ikoner överallt. Responsiv Compose-layout, dynamisk textskalning, rymliga touch-ytor.

## **Icke-funktionella krav**

- **Appstorlek:** Får vara stor så länge det är tillåtet på App Store och Google Play (STT + 3D får ingå i paketet).
- **Prestanda:** Medium tier-mobiler som mål. Lägre enheter är bonus — appen begränsas inte för lågbudget.
- **Testning:** Unit tests räcker för MVP; ingen separat systemtest-/CI-satsning i första läget.

---

# **5. Målgrupp & Föräldravyn**

## **Målgrupp**

- **Primär:** Barn ca 6–12 år.
- **Sekundär:** Föräldrar som vill ha insyn utan att tjata. (Ingen pedagog-/lärarroll i MVP.)

## **Föräldravyn**

- Nås via **Settings** bakom ett **svårt mattetal** (samma enhet; ingen separat föräldraapp/webb i MVP).
- Dashboard: lästid, mattetal, streaks, syskonprofiler.
- **Ord-logg & mattefelmönster:** Text + metadata lokalt (aldrig ljud). Visas som "ord som kan behöva övas". Laddas aldrig upp.
- Dagliga mål justerbara. **Inga push-notiser.**
- **Statistik** syns på telefonen i föräldravyn — ingen PDF för utvecklingssamtal.
- Vänlista synlig för föräldern. **Inga bonusgåvor.**

## **Notiser**

**Inga notiser och ingen FCM.** Streak och påminnelser hanteras när appen är öppen.

---

# **6. Säkerhet, integritet & publicering**

- 100 % gratis, inga IAP, ingen reklam.
- **Ingen röstlagring:** Ljud bearbetas i realtid och raderas omedelbart; aldrig i loggar/Crashlytics.
- **Användardata (framsteg, ord-logg m.m.):** Endast lokalt på telefonen — laddas aldrig upp.
- **Avatar** är inte personligt kopplad → behandlas inte som personuppgift i den meningen. **Användarnamn** väljs fritt.
- **E-post** för valfritt konto är personuppgift (GDPR) → sektion **Data och integritet** under Settings.
- **Firebase Crashlytics:** Samtyckesdialog efter **3:e appstarten** (stör inte första testsessionerna).
- **Store-kategori / åldersgräns:** Fylls i vid skapande av App Store- och Play-listningar (Kids/Families utvärderas då).

---

# **7. Framtida idéer & Vidareutveckling**

- Integration mot skolplattformar (Classroom, Vklass, Skola24) — medvetet utanför MVP.
- Utökade ämnen: NO, geografi, moderna språk med egna belöningsteman.
- Säsongsevents (Halloween, Jul, Påsk, Läslov) med exklusiv event-kosmetika.
- Fler avatar-animationer, aura/skin-slots, streak freeze, matte-stjärnregler i detalj.

---

# **8. Öppna detaljer (produktion / balans — ej kravblockerare)**

Dessa är medvetet kvar som finjustering under bygge, inte som öppna produktbeslut:

- Exakt tutorialtext, första föremål, hoppa-över-regel
- Startavatar och upplåsningskarta per uppdrag/event
- Flera föremål per slot / kroppsformsregler
- Stjärnkrav för matte och exakt stjärnkostnad per avatar/item
- STT-motor + latens efter prototyp (rangordnad lista)
- Konfidenströsklar för "fel" / "fastnat"
- Matte-inmatning och hint-timing
- Streak freeze / helger / lov
- App Store-/Play-konton och juridisk person
- Store-kategori och åldersgräns vid publicering
