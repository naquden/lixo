package se.lixo.app.domain.content

/**
 * The debug and test sample content pack: 2 avatars, 10 items over 9 equipment slots, 1 event.
 * Used by the 019/020 commonTest suite (via [se.lixo.app.content.TestPackFixture], which
 * delegates to this object) and by the debug `ContentPreviewScreen`, until the bundled pack
 * (021/022) exists.
 */
object SampleContentPack {

    val ACHIEVEMENT_IDS = setOf("ach_weekly_streak_7")

    val JSON: String = """
        {
          "schemaVersion": 1,
          "version": 42,
          "publishedAt": "2026-11-20T10:00:00Z",
          "futureField": 1,
          "strings": {
            "sv": {
              "avatar_cat.name": "Katten Lixo",
              "avatar_dog.name": "Hunden Bosse",
              "item_hat_test_cone.name": "Testhatt",
              "item_shoulders_test_cape.name": "Testcape",
              "item_gloves_test_mitt.name": "Testvante",
              "item_shirt_test_tee.name": "Testtröja",
              "item_pants_test_shorts.name": "Testshorts",
              "item_shoes_test_boot.name": "Teststövel",
              "item_hand_right_test_wand.name": "Testtrollspö",
              "item_hand_left_test_shield.name": "Testsköld",
              "item_tail_test_bow.name": "Testrosett",
              "item_hat_test_retired.name": "Pensionerad testhatt",
              "event_test_jul.name": "Testjul",
              "event_test_jul.banner": "Julfirande!"
            }
          },
          "avatars": [
            {
              "id": "avatar_cat",
              "nameKey": "avatar_cat.name",
              "order": 0,
              "humanoid": false,
              "skinHex": "#F2B880",
              "defaultTintHex": "#F5A623",
              "unlock": { "type": "default" },
              "pose": { "headBob": 1.0, "breath": 1.0, "armSwing": 1.0, "tailWag": 1.0 },
              "parts": [
                {
                  "part": "body",
                  "layers": [
                    { "bone": "TORSO", "z": "TORSO", "d": "M 0 0 L 80 0 L 80 80 L 0 80 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "head",
                  "layers": [
                    { "bone": "HEAD", "z": "HEAD", "d": "M 0 0 L 56 0 L 56 56 L 0 56 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "arms",
                  "layers": [
                    { "bone": "ARM_L", "z": "ARM", "mirror": "x", "d": "M 0 0 L 38 0 L 38 80 L 0 80 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "legs",
                  "layers": [
                    { "bone": "LEG_L", "z": "LEGS", "mirror": "x", "d": "M 0 0 L 40 0 L 40 80 L 0 80 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "ears",
                  "layers": [
                    { "bone": "HEAD", "z": "HEAD_OVER", "d": "M 8 -10 L 20 -30 L 32 -10 Z", "fill": { "role": "tint" } }
                  ]
                },
                {
                  "part": "tail",
                  "layers": [
                    { "bone": "TAIL", "z": "TAIL", "d": "M 0 0 C 10 -10 30 10 40 0", "fill": { "role": "skin" } }
                  ]
                }
              ],
              "face": { "eyeStyles": 4, "smileStyles": 4 }
            },
            {
              "id": "avatar_dog",
              "nameKey": "avatar_dog.name",
              "order": 1,
              "humanoid": false,
              "skinHex": "#C9A876",
              "defaultTintHex": "#8E6E4E",
              "unlock": { "type": "stars", "stars": 12 },
              "bones": {
                "HEAD": { "size": [60, 56] }
              },
              "pose": { "headBob": 1.0, "breath": 1.0, "armSwing": 1.0, "tailWag": 1.0 },
              "parts": [
                {
                  "part": "body",
                  "layers": [
                    { "bone": "TORSO", "z": "TORSO", "d": "M 0 0 L 80 0 L 80 80 L 0 80 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "head",
                  "layers": [
                    { "bone": "HEAD", "z": "HEAD", "d": "M 0 0 L 56 0 L 56 56 L 0 56 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "arms",
                  "layers": [
                    { "bone": "ARM_L", "z": "ARM", "mirror": "x", "d": "M 0 0 L 38 0 L 38 80 L 0 80 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "legs",
                  "layers": [
                    { "bone": "LEG_L", "z": "LEGS", "mirror": "x", "d": "M 0 0 L 40 0 L 40 80 L 0 80 Z", "fill": { "role": "skin" } }
                  ]
                },
                {
                  "part": "ears",
                  "layers": [
                    { "bone": "HEAD", "z": "HEAD_OVER", "d": "M 5 -5 L 15 -35 L 30 -5 Z", "fill": { "role": "tint" } }
                  ]
                },
                {
                  "part": "tail",
                  "layers": [
                    { "bone": "TAIL", "z": "TAIL", "d": "M 0 0 C 10 -10 30 10 40 0", "fill": { "role": "skin" } }
                  ]
                }
              ],
              "face": { "eyeStyles": 4, "smileStyles": 4 }
            }
          ],
          "items": [
            {
              "id": "item_hat_test_cone",
              "slot": "HAT",
              "rarity": "COMMON",
              "subjectTheme": "ENGLISH",
              "nameKey": "item_hat_test_cone.name",
              "palette": { "primary": "#C0392B", "secondary": "#FFFFFF", "accent": "#F1C40F" },
              "hidesParts": ["ears"],
              "layers": [
                { "bone": "HEAD", "z": "HAT", "d": "M 8 0 L 28 -40 L 48 0 Z", "fill": { "role": "primary" } }
              ],
              "pool": "standard",
              "futureItemField": true
            },
            {
              "id": "item_shoulders_test_cape",
              "slot": "SHOULDERS_BACK",
              "rarity": "UNCOMMON",
              "subjectTheme": "SWEDISH",
              "nameKey": "item_shoulders_test_cape.name",
              "palette": { "primary": "#2C3E50", "secondary": "#ECF0F1", "accent": "#E74C3C" },
              "layers": [
                { "bone": "TORSO", "z": "BACK", "d": "M -10 0 L 90 0 L 90 90 L -10 90 Z", "fill": { "role": "secondary" } }
              ],
              "pool": "achievement"
            },
            {
              "id": "item_gloves_test_mitt",
              "slot": "ARMS_GLOVES",
              "rarity": "RARE",
              "subjectTheme": "MATH",
              "nameKey": "item_gloves_test_mitt.name",
              "palette": { "primary": "#8E44AD", "secondary": "#D2B4DE", "accent": "#F5EEF8" },
              "layers": [
                { "bone": "HAND_L", "z": "HAND", "mirror": "x", "d": "M 2 2 L 36 2 L 36 30 L 2 30 Z", "fill": { "role": "primary" } }
              ],
              "pool": "standard"
            },
            {
              "id": "item_shirt_test_tee",
              "slot": "ARMOR_SHIRT",
              "rarity": "COMMON",
              "subjectTheme": "SWEDISH",
              "nameKey": "item_shirt_test_tee.name",
              "palette": { "primary": "#27AE60", "secondary": "#FFFFFF", "accent": "#145A32" },
              "pattern": "STRIPES",
              "layers": [
                { "bone": "TORSO", "z": "TORSO_OVER", "d": "M 5 5 L 75 5 L 75 75 L 5 75 Z", "fill": { "role": "primary" }, "patternClip": true, "breath": true }
              ],
              "pool": "standard"
            },
            {
              "id": "item_pants_test_shorts",
              "slot": "PANTS",
              "rarity": "COMMON",
              "subjectTheme": "SWEDISH",
              "nameKey": "item_pants_test_shorts.name",
              "palette": { "primary": "#34495E", "secondary": "#5D6D7E", "accent": "#AAB7B8" },
              "layers": [
                { "bone": "LEG_L", "z": "LEGWEAR", "mirror": "x", "d": "M 2 40 L 38 40 L 38 80 L 2 80 Z", "fill": { "role": "secondary" } }
              ],
              "pool": "standard"
            },
            {
              "id": "item_shoes_test_boot",
              "slot": "SHOES",
              "rarity": "UNCOMMON",
              "subjectTheme": "SWEDISH",
              "nameKey": "item_shoes_test_boot.name",
              "palette": { "primary": "#1B2631", "secondary": "#515A5A", "accent": "#839192" },
              "layers": [
                { "bone": "LEG_L", "z": "FOOTWEAR", "mirror": "x", "d": "M 0 60 L 40 60 L 40 80 L 0 80 Z", "fill": { "role": "primary" } }
              ],
              "pool": "standard"
            },
            {
              "id": "item_hand_right_test_wand",
              "slot": "HAND_RIGHT",
              "rarity": "EPIC",
              "subjectTheme": "MATH",
              "nameKey": "item_hand_right_test_wand.name",
              "palette": { "primary": "#9B59B6", "secondary": "#D7BDE2", "accent": "#FDFEFE" },
              "layers": [
                { "bone": "HAND_R", "z": "HAND", "d": "M 5 5 L 30 5 L 30 30 L 5 30 Z", "fill": { "role": "accent" }, "shading": "block", "depth": 6 }
              ],
              "pool": "standard"
            },
            {
              "id": "item_hand_left_test_shield",
              "slot": "HAND_LEFT",
              "rarity": "LEGENDARY",
              "subjectTheme": "MATH",
              "nameKey": "item_hand_left_test_shield.name",
              "palette": { "primary": "#F39C12", "secondary": "#FDEBD0", "accent": "#B9770E" },
              "ornament": "SPARKLE",
              "layers": [
                { "bone": "HAND_L", "z": "HAND", "d": "M 4 4 A 15 15 0 0 1 34 4 L 34 30 L 4 30 Z", "fill": { "role": "primary" } }
              ],
              "pool": "standard"
            },
            {
              "id": "item_tail_test_bow",
              "slot": "TAIL",
              "rarity": "RARE",
              "subjectTheme": "SWEDISH",
              "nameKey": "item_tail_test_bow.name",
              "palette": { "primary": "#C0392B", "secondary": "#F1948A", "accent": "#78281F" },
              "layers": [
                { "bone": "TAIL", "z": "TAIL", "d": "M 0 0 L 40 0 L 40 60 L 0 60 Z", "fill": { "role": "secondary" } }
              ],
              "pool": "event",
              "eventId": "event_test_jul"
            },
            {
              "id": "item_hat_test_retired",
              "slot": "HAT",
              "rarity": "COMMON",
              "subjectTheme": "ENGLISH",
              "nameKey": "item_hat_test_retired.name",
              "palette": { "primary": "#7F8C8D", "secondary": "#BDC3C7", "accent": "#2C3E50" },
              "layers": [
                { "bone": "HEAD", "z": "HAT", "d": "M 0 0 L 20 0 L 20 10 L 0 10 Z", "fill": { "role": "primary" } }
              ],
              "pool": "standard",
              "retired": true
            }
          ],
          "events": [
            {
              "id": "event_test_jul",
              "nameKey": "event_test_jul.name",
              "start": "2026-12-01",
              "endExclusive": "2026-12-27",
              "tz": "local",
              "itemIds": ["item_tail_test_bow"],
              "chestTheme": { "bodyHex": "#B03A2E", "trimHex": "#F1C40F", "ornament": "SPARKLE" },
              "banner": { "titleKey": "event_test_jul.banner", "bgHex": "#B03A2E", "fgHex": "#FFFFFF" },
              "dropBoost": { "eventItemChance": 0.5, "presentGiftChance": 0.5 },
              "afterEnd": "retire"
            }
          ],
          "dropRules": {
            "rarityWeights": { "COMMON": 40, "UNCOMMON": 28, "RARE": 18, "EPIC": 10, "LEGENDARY": 4 },
            "chestCostHalfPoints": 20,
            "progressRunHalfPoints": 2,
            "replayRunHalfPoints": 1,
            "pity": { "RARE": 4, "EPIC": 10, "LEGENDARY": 20 },
            "onboarding": { "levels": [1, 2, 3, 4, 5, 6, 8, 10] },
            "starUnlocks": [
              { "stars": 31, "itemId": "item_hand_right_test_wand" }
            ],
            "levelRewards": [
              { "level": 1, "itemId": "item_shirt_test_tee" },
              { "level": 2, "itemId": "item_pants_test_shorts" }
            ],
            "achievementRewards": {
              "ach_weekly_streak_7": "item_shoulders_test_cape"
            },
            "tutorialRewardItemId": "item_hat_test_cone",
            "weeklyStreakSetPieceItemIds": ["item_shoulders_test_cape"]
          }
        }
    """.trimIndent()

    /**
     * Validates [JSON] against [ACHIEVEMENT_IDS] and wraps the accepted pack as [ActiveContent].
     * Throws if the pack is rejected (it never should be: this fixture is hand-validated).
     */
    fun activeContent(): ActiveContent {
        val result = ContentPackValidator.validate(JSON, ACHIEVEMENT_IDS, previous = null, requireNewerVersion = false)
        return when (result) {
            is ValidationResult.Accepted -> ActiveContent.from(result.pack)
            is ValidationResult.Rejected -> error("Sample content pack rejected: ${result.reason}")
        }
    }
}
