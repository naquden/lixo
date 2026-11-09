import copy
import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.content_pipeline.upload_content import validate_bundle
from tools.item_pipeline.generate_wardrobe_items import (
    ACHIEVEMENT_REWARDS,
    EPIC_ACCENT_GOLD,
    EPIC_ACCENT_SILVER,
    HANDMADE_ITEM_IDS,
    LEGENDARY_COLORWAYS,
    RARITIES,
    SLOTS,
    bundled_item_ids,
    generate_catalog,
    slug_key,
)

REPO_ROOT = Path(__file__).resolve().parents[2]

SEED_BUNDLE_PATH = (
    Path(__file__).resolve().parents[2]
    / "composeApp/src/commonMain/composeResources/files/seed_campaign_content.json"
)


def load_seed_bundle() -> dict:
    # The bundled JSON is the source of truth for curriculum content (the generator script was removed).
    return json.loads(SEED_BUNDLE_PATH.read_text(encoding="utf-8"))


def math_levels_by_index(bundle: dict) -> dict:
    math_levels = sorted(
        (lvl for lvl in bundle["levels"] if lvl["subject"] == "MATH"), key=lambda lvl: lvl["order"]
    )
    return {n: lvl for n, lvl in enumerate(math_levels, start=1)}


def math_problems_for_index(bundle: dict, n: int) -> list:
    tasks_by_id = {t["id"]: t for t in bundle["tasks"]}
    return tasks_by_id[math_levels_by_index(bundle)[n]["taskId"]]["mathProblems"]


class LixoPipelineAndSpecificationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.repo_root = Path(__file__).resolve().parents[2]

    def test_seed_bundle_passes_all_mvp_constraints(self) -> None:
        bundle = load_seed_bundle()
        self.assertEqual(len(bundle["levels"]), 160)
        self.assertEqual(len(bundle["tasks"]), 160)
        self.assertEqual(sorted(lvl["order"] for lvl in bundle["levels"]), list(range(1, 161)))
        self.assertGreaterEqual(len(bundle["wardrobeItems"]), 100)
        task_ids = {t["id"] for t in bundle["tasks"]}
        for lvl in bundle["levels"]:
            self.assertIn(lvl["taskId"], task_ids)
        riddarvagn = next(d for d in bundle["dictionary"] if d["wordNormalized"] == "riddarvagn")
        self.assertEqual(riddarvagn["syllables"], "rid-dar-vagn")

    def test_seed_bundle_tasks_have_no_reward_item_id(self) -> None:
        bundle = load_seed_bundle()
        for t in bundle["tasks"]:
            self.assertNotIn("rewardItemId", t)
        self.assertEqual(validate_bundle(bundle), [])

    def test_validate_bundle_rejects_task_with_reward_item_id(self) -> None:
        bundle = copy.deepcopy(load_seed_bundle())
        bundle["tasks"][0]["rewardItemId"] = "item_hat_2"
        errors = validate_bundle(bundle)
        matching = [e for e in errors if bundle["tasks"][0]["id"] in e and "rewardItemId" in e]
        self.assertEqual(len(matching), 1)

    def test_swedish_stories_are_unique_and_progress_in_difficulty(self) -> None:
        bundle = load_seed_bundle()
        tasks_by_id = {t["id"]: t for t in bundle["tasks"]}
        sv_levels = sorted(
            (lvl for lvl in bundle["levels"] if lvl["subject"] == "SWEDISH"), key=lambda lvl: lvl["order"]
        )
        self.assertEqual(len(sv_levels), 40)
        texts = [tasks_by_id[lvl["taskId"]]["textContent"] for lvl in sv_levels]
        self.assertEqual(len(set(texts)), 40, "Every Swedish level must have its own story")

        def avg_words_per_sentence(course_id: str) -> float:
            course_texts = [
                tasks_by_id[lvl["taskId"]]["textContent"] for lvl in sv_levels if lvl["courseId"] == course_id
            ]
            words = sum(len(re.findall(r"\w+", t)) for t in course_texts)
            sentences = sum(len([s for s in re.split(r"[.!?]+", t) if re.search(r"\w", s)]) for t in course_texts)
            return words / sentences

        sv1, sv2, sv3 = (avg_words_per_sentence(c) for c in ("course_sv_1", "course_sv_2", "course_sv_3"))
        self.assertLess(sv1, sv2)
        self.assertLess(sv2, sv3)

    def test_math_parity_fixture(self) -> None:
        bundle = load_seed_bundle()
        by_index = math_levels_by_index(bundle)
        self.assertEqual(len(by_index), 100)
        self.assertEqual(by_index[1]["order"], 2)
        self.assertEqual(by_index[31]["order"], 76)
        self.assertEqual(by_index[40]["order"], 98)
        self.assertEqual(by_index[41]["order"], 101)
        self.assertEqual(by_index[92]["order"], 152)
        self.assertEqual(by_index[100]["order"], 160)

        n1_prompts = [
            "Vad blir 1 + 1?",
            "Vad blir 1 + 2?",
            "Vad blir 2 + 1?",
            "Vad blir 1 + 3?",
            "Vad blir 3 + 1?",
            "Vad blir 1 + 4?",
        ]
        n1_choices = [
            ["1", "3", "2", "4"],
            ["2", "4", "5", "3"],
            ["3", "2", "4", "5"],
            ["3", "4", "5", "6"],
            ["3", "5", "4", "6"],
            ["4", "6", "7", "5"],
        ]
        n1_problems = math_problems_for_index(bundle, 1)
        self.assertEqual([p["promptSv"] for p in n1_problems], n1_prompts)
        self.assertEqual([p["choices"] for p in n1_problems], n1_choices)

        n31_prompts = [
            "Vad blir 2 - 1?",
            "Vad blir 3 - 1?",
            "Timvisaren pekar rakt på 3 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 4 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 5 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 6 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 7 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
            "Timvisaren pekar rakt på 8 och minutvisaren pekar rakt upp på 12. Vad är klockan?",
        ]
        n31_answers = ["1", "2", "03:00", "04:00", "05:00", "06:00", "07:00", "08:00"]
        n31_problems = math_problems_for_index(bundle, 31)
        self.assertEqual([p["promptSv"] for p in n31_problems], n31_prompts)
        self.assertEqual([p["correctAnswer"] for p in n31_problems], n31_answers)

        n92_prompts = [
            "Timvisaren är mellan 7 och 8, och minutvisaren pekar på 4. Vad är klockan?",
            "Timvisaren är mellan 10 och 11, och minutvisaren pekar på 7. Vad är klockan?",
            "Timvisaren är mellan 2 och 3, och minutvisaren pekar på 2. Vad är klockan?",
            "Timvisaren närmar sig 6 och minutvisaren pekar på 9. Vad är klockan?",
            "Timvisaren är mellan 8 och 9, och minutvisaren pekar på 1. Vad är klockan?",
            "Timvisaren är mellan 11 och 12, och minutvisaren pekar på 10. Vad är klockan?",
            "Timvisaren är mellan 3 och 4, och minutvisaren pekar på 5. Vad är klockan?",
            "Timvisaren är mellan 6 och 7, och minutvisaren pekar på 8. Vad är klockan?",
            "Timvisaren är strax efter 9 och minutvisaren pekar på 3. Vad är klockan?",
            "Timvisaren är mellan 12 och 1, och minutvisaren pekar på 11. Vad är klockan?",
        ]
        n92_answers = [
            "07:20", "10:35", "02:10", "05:45", "08:05",
            "11:50", "03:25", "06:40", "09:15", "12:55",
        ]
        n92_problems = math_problems_for_index(bundle, 92)
        self.assertEqual([p["promptSv"] for p in n92_problems], n92_prompts)
        self.assertEqual([p["correctAnswer"] for p in n92_problems], n92_answers)

    def test_math_constraints(self) -> None:
        bundle = load_seed_bundle()
        by_index = math_levels_by_index(bundle)
        self.assertEqual(len(by_index), 100)

        tasks_by_id = {t["id"]: t for t in bundle["tasks"]}
        types_seen = set()
        for n, lvl in by_index.items():
            mps = tasks_by_id[lvl["taskId"]]["mathProblems"]
            self.assertLessEqual(len(mps), 10)
            if n <= 5:
                self.assertEqual(len(mps), 6)
            positions = {mp["choices"].index(mp["correctAnswer"]) for mp in mps}
            self.assertGreater(len(positions), 1, f"n={n} choice position never varies")
            for mp in mps:
                types_seen.add(mp["type"].lower())
                self.assertIn(mp["correctAnswer"], mp["choices"])
                self.assertEqual(len(set(mp["choices"])), len(mp["choices"]))

        self.assertEqual(types_seen, {
            "addition", "subtraction", "ten_crossing", "clock", "money", "word_problem",
        })

    def test_swedish_strings_xml_and_firestore_rules_exist(self) -> None:
        strings_xml = (
            self.repo_root
            / "composeApp"
            / "src"
            / "commonMain"
            / "composeResources"
            / "values"
            / "strings.xml"
        )
        self.assertTrue(strings_xml.exists())
        content = strings_xml.read_text(encoding="utf-8")
        self.assertIn("Jag har läst klart", content)
        self.assertIn("Föräldravy", content)

        rules_file = self.repo_root / "firebase" / "firestore.rules"
        self.assertTrue(rules_file.exists())
        rules_text = rules_file.read_text(encoding="utf-8")
        self.assertIn("public_avatars", rules_text)
        self.assertNotIn("match /wardrobe/", rules_text)

    def test_firebase_project_id_configured_across_all_configs(self) -> None:
        firebaserc = json.loads((self.repo_root / ".firebaserc").read_text(encoding="utf-8"))
        self.assertEqual(firebaserc["projects"]["default"], "lixo-fd45d")

        firebase_json = json.loads((self.repo_root / "firebase.json").read_text(encoding="utf-8"))
        self.assertEqual(firebase_json["firestore"]["rules"], "firebase/firestore.rules")

        google_services = json.loads(
            (self.repo_root / "composeApp" / "google-services.json").read_text(encoding="utf-8")
        )
        self.assertEqual(google_services["project_info"]["project_id"], "lixo-fd45d")

        plist_text = (self.repo_root / "iosApp" / "GoogleService-Info.plist").read_text(encoding="utf-8")
        self.assertIn("<string>lixo-fd45d</string>", plist_text)


class WardrobeItemGeneratorTests(unittest.TestCase):
    def test_deterministic_with_fixed_seed(self) -> None:
        catalog_a = generate_catalog(per_slot=64, seed=42)
        catalog_b = generate_catalog(per_slot=64, seed=42)
        self.assertEqual(catalog_a, catalog_b)

    def test_different_seed_changes_output(self) -> None:
        catalog_a = generate_catalog(per_slot=64, seed=42)
        catalog_b = generate_catalog(per_slot=64, seed=7)
        self.assertNotEqual(catalog_a, catalog_b)

    def test_per_slot_count_and_full_style_and_rarity_coverage(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        self.assertEqual(len(SLOTS), 9)
        for slot in SLOTS:
            items = catalog[slot.slot_id]
            self.assertEqual(len(items), 64, f"slot {slot.slot_id} must have 64 items")

            styles_present = {i.style_index for i in items}
            self.assertEqual(styles_present, set(range(8)), f"slot {slot.slot_id} missing a style")

            rarities_present = {i.rarity for i in items}
            self.assertEqual(rarities_present, set(RARITIES), f"slot {slot.slot_id} missing a rarity")

    def test_rarity_split_is_approximately_40_25_17_12_6_with_at_least_one_each(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        for slot in SLOTS:
            items = catalog[slot.slot_id]
            counts = {r: sum(1 for i in items if i.rarity == r) for r in RARITIES}
            for r in RARITIES:
                self.assertGreaterEqual(counts[r], 1, f"slot {slot.slot_id} rarity {r} must have >= 1 item")
            self.assertEqual(sum(counts.values()), 64)
            # Roughly matches the target split (40/25/17/12/6%).
            self.assertGreater(counts["COMMON"], counts["LEGENDARY"])
            self.assertGreaterEqual(counts["COMMON"], 20)
            self.assertLessEqual(counts["LEGENDARY"], 8)

    def test_no_duplicate_style_colorway_pattern_combo_within_slot(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        for slot in SLOTS:
            items = catalog[slot.slot_id]
            combos = [(i.style_index, i.colorway_name, i.pattern) for i in items]
            self.assertEqual(len(combos), len(set(combos)), f"slot {slot.slot_id} has a duplicate combo")

    def test_no_duplicate_name_sv_within_slot(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        for slot in SLOTS:
            items = catalog[slot.slot_id]
            names = [i.name_sv for i in items]
            self.assertEqual(
                len(names), len(set(names)), f"slot {slot.slot_id} has a duplicate nameSv"
            )

    def test_only_a_small_common_uncommon_subset_is_star_unlockable(self) -> None:
        # Only ~6 COMMON + ~2 UNCOMMON items per slot should star-unlock at all; every other
        # item (including all RARE/EPIC/LEGENDARY) must be unlockStarRequirement == 0, i.e. only
        # obtainable via level rewards, chests, or gifts -- otherwise the whole catalog
        # auto-unlocks once total stars is high enough.
        catalog = generate_catalog(per_slot=64, seed=42)
        for slot in SLOTS:
            items = catalog[slot.slot_id]
            star_unlockable = [i for i in items if i.unlock_star_requirement > 0]
            self.assertLessEqual(len(star_unlockable), 8, f"slot {slot.slot_id} has too many star-unlockable items")
            self.assertGreater(len(star_unlockable), 0, f"slot {slot.slot_id} has no star-unlockable items")
            for item in star_unlockable:
                self.assertIn(item.rarity, ("COMMON", "UNCOMMON"))
                self.assertIsNone(item.unlock_achievement_id)
            for item in items:
                if item.rarity in ("RARE", "EPIC", "LEGENDARY"):
                    self.assertEqual(item.unlock_star_requirement, 0)
            thresholds = sorted(i.unlock_star_requirement for i in star_unlockable)
            self.assertEqual(len(thresholds), len(set(thresholds)), f"slot {slot.slot_id} has duplicate star thresholds")
            self.assertLessEqual(thresholds[-1], 250)

    def test_star_unlock_thresholds_are_staggered_across_slots(self) -> None:
        # Different slots' lowest star-unlock threshold should not all coincide -- otherwise
        # every slot's first item would unlock on the exact same star count.
        catalog = generate_catalog(per_slot=64, seed=42)
        first_thresholds = set()
        for slot in SLOTS:
            items = catalog[slot.slot_id]
            star_unlockable = [i.unlock_star_requirement for i in items if i.unlock_star_requirement > 0]
            first_thresholds.add(min(star_unlockable))
        self.assertGreater(len(first_thresholds), 1, "all slots unlock their first item on the same star count")

    def test_star_unlock_thresholds_form_one_global_widening_curve(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        all_items = [i for slot in SLOTS for i in catalog[slot.slot_id] if i.unlock_star_requirement > 0]
        all_items.sort(key=lambda i: i.unlock_star_requirement)

        self.assertEqual(len(all_items), 72)
        thresholds = [i.unlock_star_requirement for i in all_items]
        self.assertEqual(len(thresholds), len(set(thresholds)), "star thresholds must be unique")
        self.assertGreaterEqual(thresholds[0], 25)
        self.assertLessEqual(thresholds[-1], 250)

        for i in range(2, len(thresholds)):
            gap = thresholds[i] - thresholds[i - 1]
            prev_gap = thresholds[i - 1] - thresholds[i - 2]
            self.assertGreaterEqual(gap, prev_gap - 1, f"gap at index {i} must not shrink by more than 1")

        for i in range(1, len(all_items)):
            self.assertNotEqual(
                all_items[i].slot.slot_id,
                all_items[i - 1].slot.slot_id,
                "consecutive star unlocks must be different slots",
            )

        max_common = max(i.unlock_star_requirement for i in all_items if i.rarity == "COMMON")
        min_uncommon = min(i.unlock_star_requirement for i in all_items if i.rarity == "UNCOMMON")
        self.assertLess(max_common, min_uncommon)

    def test_item_ids_are_unique_and_follow_convention(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        all_ids = [i.item_id for slot in SLOTS for i in catalog[slot.slot_id]]
        self.assertEqual(len(all_ids), len(set(all_ids)))
        handmade_ids = {
            "item_hat_starter_explorer",
            "item_hand_right_math_sword_1",
            "item_shoulders_back_sv_cape_1",
            "item_hat_weekly_hero",
            "item_armor_shirt_crystal_voice",
            "item_hand_right_clock_money",
        }
        self.assertFalse(handmade_ids & set(all_ids))
        for slot in SLOTS:
            for item in catalog[slot.slot_id]:
                style_key = slug_key(item.style_name)
                colorway_key = slug_key(item.colorway_name)
                self.assertRegex(style_key, r"^[a-z0-9]+$")
                self.assertRegex(colorway_key, r"^[a-z0-9]+$")
                self.assertEqual(item.item_id, f"item_{slot.slot_id}_{style_key}_{colorway_key}")
                self.assertEqual(item.mesh_node_name, f"{slot.socket_name}_{style_key}_{colorway_key}")

    def test_item_ids_are_stable_across_per_slot_changes(self) -> None:
        # Content-derived ids: the same (slot, style, colorway) item keeps its id regardless of
        # catalog size. per_slot must be >= 44 so every ACHIEVEMENT_REWARDS rarity (11 LEGENDARY)
        # still has an eligible item; smaller sizes make _assign_achievement_items raise.
        large = generate_catalog(per_slot=64, seed=42)
        for other_size in (48, 80):
            other = generate_catalog(per_slot=other_size, seed=42)
            shared = 0
            for slot in SLOTS:
                by_combo_other = {(i.style_name, i.colorway_name): i.item_id for i in other[slot.slot_id]}
                by_combo_large = {(i.style_name, i.colorway_name): i.item_id for i in large[slot.slot_id]}
                for combo, item_id in by_combo_other.items():
                    if combo in by_combo_large:
                        shared += 1
                        self.assertEqual(item_id, by_combo_large[combo], f"{combo} per_slot={other_size}")
            self.assertGreater(shared, 0, f"per_slot={other_size} shares no combos with 64")

    def test_slug_key(self) -> None:
        self.assertEqual(slug_key("Hög Hatt"), "hoghatt")
        self.assertEqual(slug_key("Tunika med Bälte"), "tunikamedbalte")
        self.assertEqual(slug_key("Komet-svans"), "kometsvans")
        self.assertEqual(slug_key("Isblå"), "isbla")
        self.assertEqual(slug_key("Krona"), "krona")

    def test_exactly_one_weekly_streak_set_piece_per_slot(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        for slot in SLOTS:
            items = catalog[slot.slot_id]
            set_pieces = [i for i in items if i.is_weekly_streak_set_piece]
            self.assertEqual(len(set_pieces), 1, f"slot {slot.slot_id} must have exactly 1 set piece")
            self.assertEqual(set_pieces[0].rarity, "LEGENDARY")

    def test_achievement_items_reserved(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        all_items = [i for slot in SLOTS for i in catalog[slot.slot_id]]

        self.assertEqual(len(ACHIEVEMENT_REWARDS), 44)
        ids_seen = [achievement_id for achievement_id, _rarity in ACHIEVEMENT_REWARDS]
        self.assertEqual(len(ids_seen), len(set(ids_seen)), "achievement ids must be unique")

        matched_slots = []
        for achievement_id, rarity in ACHIEVEMENT_REWARDS:
            matches = [i for i in all_items if i.unlock_achievement_id == achievement_id]
            self.assertEqual(len(matches), 1, f"expected exactly 1 item for {achievement_id}")
            item = matches[0]
            self.assertEqual(item.rarity, rarity)
            self.assertEqual(item.unlock_star_requirement, 0)
            self.assertFalse(item.is_weekly_streak_set_piece)
            matched_slots.append(item.slot.slot_id)

        reserved_count = len([i for i in all_items if i.unlock_achievement_id is not None])
        self.assertEqual(reserved_count, 44)

        star_ids = [achievement_id for achievement_id, _rarity in ACHIEVEMENT_REWARDS[:8]]
        self.assertEqual(
            star_ids,
            [f"ach_stars_{n}" for n in (20, 45, 75, 120, 175, 250, 350, 480)],
        )
        self.assertEqual(matched_slots[:8], [slot.slot_id for slot in SLOTS[:8]])

        for a, b in zip(matched_slots, matched_slots[1:]):
            self.assertNotEqual(a, b, "consecutive achievement rewards must be in different slots")

    def test_rejects_per_slot_smaller_than_rarity_count(self) -> None:
        with self.assertRaises(ValueError):
            generate_catalog(per_slot=3, seed=42)

    def test_ids_names_rarities_unchanged(self) -> None:
        # Plan 011 section 3: the visual ladder (pattern/ornament/secondary accent) must never
        # shift ids, names, rarities, star thresholds or achievement ids -- all new visual
        # randomness goes through a separate per-item rng, not the main per-slot rng.
        fixture_path = Path(__file__).resolve().parent / "fixtures" / "catalog_ids_v1.json"
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))

        catalog = generate_catalog(per_slot=64, seed=42)
        generated = sorted(
            (
                {
                    "id": i.item_id,
                    "nameSv": i.name_sv,
                    "rarity": i.rarity,
                    "unlockStarRequirement": i.unlock_star_requirement,
                    "unlockAchievementId": i.unlock_achievement_id,
                }
                for slot in SLOTS
                for i in catalog[slot.slot_id]
            ),
            key=lambda d: d["id"],
        )
        self.assertEqual(generated, fixture)

    def test_visual_ladder(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        all_items = [i for slot in SLOTS for i in catalog[slot.slot_id]]
        epic_accents = set()

        for item in all_items:
            if item.rarity == "COMMON":
                self.assertEqual(item.pattern, "NONE")
                self.assertEqual(item.ornament, "NONE")
            elif item.rarity == "UNCOMMON":
                self.assertEqual(item.ornament, "TRIM")
                self.assertIn(item.pattern, {"NONE", "STRIPES", "DOTS"})
            elif item.rarity == "RARE":
                self.assertEqual(item.ornament, "GEM")
                self.assertNotEqual(item.pattern, "NONE")
            elif item.rarity == "EPIC":
                self.assertEqual(item.ornament, "GLOW")
                self.assertNotEqual(item.pattern, "NONE")
                self.assertIn(item.secondary_color_hex, {EPIC_ACCENT_GOLD, EPIC_ACCENT_SILVER})
                epic_accents.add(item.secondary_color_hex)
            else:  # LEGENDARY
                self.assertEqual(item.ornament, "SPARKLE")
                self.assertNotEqual(item.pattern, "NONE")
                self.assertIn(item.colorway_name, LEGENDARY_COLORWAYS)
                self.assertEqual(item.scale_multiplier, 1.15)

        self.assertEqual(epic_accents, {EPIC_ACCENT_GOLD, EPIC_ACCENT_SILVER})


class ContentPackPipelineTests(unittest.TestCase):
    """doc/plans/021-content-pack-authoring-pipeline.md."""

    @classmethod
    def setUpClass(cls) -> None:
        from tools.content_pipeline.pack import build_pack, gen_catalog, validate_pack
        from tools.content_pipeline.pack.templates.avatars import body as avatar_body
        from tools.content_pipeline.pack.templates.slots import SLOT_TEMPLATES

        cls.build_pack = build_pack
        cls.gen_catalog = gen_catalog
        cls.validate_pack = validate_pack
        cls.avatar_body = avatar_body
        cls.slot_templates = SLOT_TEMPLATES
        cls.pack_dir = REPO_ROOT / "tools" / "content_pipeline" / "pack"

    def test_build_is_deterministic(self) -> None:
        self.assertEqual(self.build_pack.build(1), self.build_pack.build(1))

    def test_every_template_passes_bounds_and_caps(self) -> None:
        for slot_id, styles in self.slot_templates.items():
            for style in styles:
                layers = style.layers({})
                self.assertLessEqual(len(layers), 24, f"{slot_id}:{style.key} has too many layers")
                pattern_clips = sum(1 for l in layers if l.get("patternClip"))
                self.assertEqual(
                    pattern_clips, 1, f"{slot_id}:{style.key} must have exactly one patternClip layer"
                )
                for layer in layers:
                    box = self.validate_pack.STANDARD_BONE_BOX[layer["bone"]]
                    errors = self.validate_pack.layer_errors(layer, box, allowed_roles=None)
                    self.assertEqual(errors, [], f"{slot_id}:{style.key}: {errors}")

        avatar_parts = list(self.avatar_body.STANDARD_BODY) + list(self.avatar_body.EARS.values()) + [
            self.avatar_body.DEFAULT_TAIL
        ]
        for part in avatar_parts:
            self.assertLessEqual(len(part["layers"]), 24)
            for layer in part["layers"]:
                box = self.validate_pack.STANDARD_BONE_BOX[layer["bone"]]
                errors = self.validate_pack.layer_errors(
                    layer, box, allowed_roles=self.validate_pack.AVATAR_PART_ROLES
                )
                self.assertEqual(errors, [], f"part {part['part']}: {errors}")

    def test_pack_ids_names_rarities_unchanged(self) -> None:
        catalog = generate_catalog(per_slot=64, seed=42)
        committed = json.loads((self.pack_dir / "catalog" / "generated.json").read_text(encoding="utf-8"))
        committed_by_id = {row["id"]: row for row in committed}

        ids_seen = set()
        for slot in SLOTS:
            for item in catalog[slot.slot_id]:
                row = committed_by_id.get(item.item_id)
                self.assertIsNotNone(row, f"missing committed row for {item.item_id}")
                self.assertEqual(row["rarity"], item.rarity)
                ids_seen.add(item.item_id)
        self.assertEqual(ids_seen, set(committed_by_id))

        strings = json.loads((self.pack_dir / "strings" / "generated.sv.json").read_text(encoding="utf-8"))
        for slot in SLOTS:
            for item in catalog[slot.slot_id]:
                self.assertEqual(strings[f"{item.item_id}.name"], item.name_sv)

        drop_rules = json.loads((self.pack_dir / "drop_rules.json").read_text(encoding="utf-8"))
        recomputed_star_unlocks = {
            (s["stars"], s["itemId"]) for s in self.gen_catalog._star_unlocks(catalog)
        }
        committed_star_unlocks = {(s["stars"], s["itemId"]) for s in drop_rules["starUnlocks"]}
        self.assertEqual(recomputed_star_unlocks, committed_star_unlocks)

        recomputed_achievements = self.gen_catalog._achievement_rewards(catalog)
        self.assertEqual(recomputed_achievements, drop_rules["achievementRewards"])

    def test_level_rewards_cover_1_to_160(self) -> None:
        drop_rules = json.loads((self.pack_dir / "drop_rules.json").read_text(encoding="utf-8"))
        level_rewards = drop_rules["levelRewards"]
        levels = sorted(r["level"] for r in level_rewards)
        self.assertEqual(levels, list(range(1, 161)))

        all_item_ids = {row["id"] for row in json.loads((self.pack_dir / "catalog" / "handmade.json").read_text(encoding="utf-8"))}
        all_item_ids |= {row["id"] for row in json.loads((self.pack_dir / "catalog" / "generated.json").read_text(encoding="utf-8"))}
        for r in level_rewards:
            self.assertIn(r["itemId"], all_item_ids)

        catalog = generate_catalog(per_slot=64, seed=42)
        self.assertEqual(level_rewards, self.gen_catalog.replay_level_rewards(catalog))

    def test_stability_diff(self) -> None:
        pack = json.loads(self.build_pack.build(5).decode("utf-8"))
        baseline = copy.deepcopy(pack)
        baseline["version"] = 4

        self.assertEqual(self.validate_pack.stability_errors(pack, baseline), [])

        removed = copy.deepcopy(pack)
        removed["items"] = removed["items"][1:]
        errors = self.validate_pack.stability_errors(removed, baseline)
        self.assertTrue(any("removed item id" in e for e in errors))

        changed_reward = copy.deepcopy(pack)
        changed_reward["dropRules"]["levelRewards"][0] = {
            "level": changed_reward["dropRules"]["levelRewards"][0]["level"],
            "itemId": "item_hat_starter_explorer"
            if changed_reward["dropRules"]["levelRewards"][0]["itemId"] != "item_hat_starter_explorer"
            else pack["items"][-1]["id"],
        }
        errors = self.validate_pack.stability_errors(changed_reward, baseline)
        self.assertTrue(any("levelRewards" in e for e in errors))

        changed_slot = copy.deepcopy(pack)
        changed_slot["items"][0] = dict(changed_slot["items"][0])
        changed_slot["items"][0]["slot"] = "tail" if changed_slot["items"][0]["slot"] != "tail" else "hat"
        errors = self.validate_pack.stability_errors(changed_slot, baseline)
        self.assertTrue(any("slot changed" in e for e in errors))

        same_version = copy.deepcopy(pack)
        same_version["version"] = baseline["version"]
        errors = self.validate_pack.stability_errors(same_version, baseline)
        self.assertTrue(any("not greater than" in e for e in errors))

    def test_bundled_snapshot_up_to_date(self) -> None:
        bundled_path = (
            REPO_ROOT
            / "composeApp"
            / "src"
            / "commonMain"
            / "composeResources"
            / "files"
            / "content_pack.json"
        )
        bundled_bytes = bundled_path.read_bytes()
        bundled = json.loads(bundled_bytes.decode("utf-8"))
        fresh = self.build_pack.build(bundled["version"], bundled.get("publishedAt", ""))
        self.assertEqual(bundled_bytes, fresh)

        fixture_path = REPO_ROOT / "tools" / "tests" / "fixtures" / "mini_pack.json"
        fixture = json.loads(fixture_path.read_bytes().decode("utf-8"))
        self.assertEqual(fixture, self.build_pack.mini_pack(bundled))

    def test_bundled_pack_validates(self) -> None:
        bundled_path = (
            REPO_ROOT
            / "composeApp"
            / "src"
            / "commonMain"
            / "composeResources"
            / "files"
            / "content_pack.json"
        )
        pack = json.loads(bundled_path.read_text(encoding="utf-8"))
        self.assertEqual(self.validate_pack.validate(pack), [])

    def test_handmade_json_ids_match(self) -> None:
        handmade = json.loads((self.pack_dir / "catalog" / "handmade.json").read_text(encoding="utf-8"))
        ids = {row["id"] for row in handmade}
        self.assertEqual(ids, set(HANDMADE_ITEM_IDS))

    def test_jul_2026_event_in_bundled_pack(self) -> None:
        bundled_path = (
            REPO_ROOT
            / "composeApp"
            / "src"
            / "commonMain"
            / "composeResources"
            / "files"
            / "content_pack.json"
        )
        pack = json.loads(bundled_path.read_text(encoding="utf-8"))

        self.assertEqual(len(pack["events"]), 1)
        event = pack["events"][0]
        self.assertEqual(event["id"], "event_jul_2026")
        self.assertEqual(len(event["itemIds"]), 9)

        items_by_id = {item["id"]: item for item in pack["items"]}
        for item_id in event["itemIds"]:
            item = items_by_id[item_id]
            self.assertEqual(item["pool"], "event")
            self.assertEqual(item["eventId"], "event_jul_2026")

        sv = pack["strings"]["sv"]
        self.assertEqual(sv[event["banner"]["titleKey"]], "God Jul!")
        for item_id in event["itemIds"]:
            self.assertIn(items_by_id[item_id]["nameKey"], sv)

        self.assertEqual(self.validate_pack.validate(pack), [])

    def test_event_errors_reject_bad_event(self) -> None:
        pack = json.loads(self.build_pack.build(1).decode("utf-8"))
        event = next(e for e in pack["events"] if e["id"] == "event_jul_2026")
        items_by_id = {item["id"]: item for item in pack["items"]}

        bad_window = dict(event)
        bad_window["endExclusive"] = bad_window["start"]
        errors = self.validate_pack.event_errors(bad_window, items_by_id)
        self.assertTrue(any("start must be before endExclusive" in e for e in errors))

        bad_hex = copy.deepcopy(event)
        bad_hex["banner"]["bgHex"] = "not-a-hex"
        errors = self.validate_pack.event_errors(bad_hex, items_by_id)
        self.assertTrue(any("banner.bgHex invalid" in e for e in errors))

        bad_chance = copy.deepcopy(event)
        bad_chance["dropBoost"]["eventItemChance"] = 1.5
        errors = self.validate_pack.event_errors(bad_chance, items_by_id)
        self.assertTrue(any("dropBoost.eventItemChance out of range" in e for e in errors))


class UploadPackTests(unittest.TestCase):
    """doc/plans/024-cloud-content-sync.md: `tools/content_pipeline/pack/upload_pack.py`."""

    @classmethod
    def setUpClass(cls) -> None:
        from tools.content_pipeline.pack import upload_pack

        cls.upload_pack = upload_pack
        cls.bundled_path = (
            REPO_ROOT
            / "composeApp"
            / "src"
            / "commonMain"
            / "composeResources"
            / "files"
            / "content_pack.json"
        )
        cls.bundled_text = cls.bundled_path.read_text(encoding="utf-8")

    def test_split_chunks_round_trips(self) -> None:
        chunks = self.upload_pack.split_chunks(self.bundled_text)
        self.assertEqual("".join(chunks), self.bundled_text)
        for chunk in chunks:
            self.assertLessEqual(len(chunk.encode("utf-8")), self.upload_pack.CHUNK_MAX_BYTES)
            # Every chunk must itself be valid UTF-8 (no split multi-byte sequence).
            self.assertEqual(chunk.encode("utf-8").decode("utf-8"), chunk)

        big = ("åäö🙂abc" * 400_000)
        self.assertGreater(len(big.encode("utf-8")), 2_000_000)
        big_chunks = self.upload_pack.split_chunks(big)
        self.assertEqual("".join(big_chunks), big)
        for chunk in big_chunks:
            self.assertLessEqual(len(chunk.encode("utf-8")), self.upload_pack.CHUNK_MAX_BYTES)
            self.assertEqual(chunk.encode("utf-8").decode("utf-8"), chunk)

    def test_build_pack_writes_puts_meta_last_with_correct_fields(self) -> None:
        pack_bytes = self.bundled_text.encode("utf-8")
        writes = self.upload_pack.build_pack_writes(pack_bytes, "projects/p/databases/(default)/documents", "2026-01-01T00:00:00Z")

        pack = json.loads(self.bundled_text)
        schema = pack["schemaVersion"]
        version = pack["version"]

        meta_write = writes[-1]
        self.assertEqual(meta_write["updateMask"]["fieldPaths"], ["packs.`1`"])
        entry_fields = meta_write["update"]["fields"]["packs"]["mapValue"]["fields"][str(schema)]["mapValue"]["fields"]
        chunk_writes = writes[:-1]
        self.assertEqual(entry_fields["chunkCount"]["integerValue"], str(len(chunk_writes)))
        self.assertEqual(entry_fields["sha256"]["stringValue"], hashlib.sha256(pack_bytes).hexdigest())

        for i, write in enumerate(chunk_writes):
            expected_name = f"projects/p/databases/(default)/documents/content_chunks/{schema}_{version}_{i}"
            self.assertEqual(write["update"]["name"], expected_name)

    def test_stability_failure_blocks_upload(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            published_dir = Path(tmp) / "published"
            published_dir.mkdir()

            old_pack = json.loads(self.bundled_text)
            old_version = old_pack["version"]
            extra_item = dict(old_pack["items"][0])
            extra_item["id"] = "item_hat_upload_pack_stability_extra"
            old_pack["items"] = old_pack["items"] + [extra_item]
            (published_dir / f"pack_v{old_version}.json").write_text(
                json.dumps(old_pack, sort_keys=True, separators=(",", ":"), ensure_ascii=False), encoding="utf-8"
            )

            new_pack = json.loads(self.bundled_text)
            new_pack["version"] = old_version + 1
            new_bytes = json.dumps(new_pack, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            pack_path = Path(tmp) / "content_pack.json"
            pack_path.write_bytes(new_bytes)

            with mock.patch.object(self.upload_pack, "commit_writes") as commit_mock, mock.patch.object(
                self.upload_pack, "_load_firebase_cli_access_token", return_value="fake-token"
            ):
                code = self.upload_pack.main(
                    ["--pack", str(pack_path), "--published-dir", str(published_dir)]
                )

            self.assertEqual(code, 1)
            commit_mock.assert_not_called()
            self.assertFalse((published_dir / f"pack_v{old_version + 1}.json").exists())

    def test_success_path_commits_chunks_before_meta_and_prunes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            published_dir = Path(tmp) / "published"
            published_dir.mkdir()

            old_pack = json.loads(self.bundled_text)
            # Pre-populate enough published versions (well above the real bundled version, which
            # may be as low as 1) that pruning actually has something to do -- `build_prune_writes`
            # only emits deletes beyond `KEEP_VERSIONS`.
            base_version = 100
            for v in range(base_version - self.upload_pack.KEEP_VERSIONS - 1, base_version + 1):
                dated = dict(old_pack)
                dated["version"] = v
                (published_dir / f"pack_v{v}.json").write_text(
                    json.dumps(dated, sort_keys=True, separators=(",", ":"), ensure_ascii=False), encoding="utf-8"
                )
            old_version = base_version

            new_pack = json.loads(self.bundled_text)
            new_pack["version"] = old_version + 1
            new_bytes = json.dumps(new_pack, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            pack_path = Path(tmp) / "content_pack.json"
            pack_path.write_bytes(new_bytes)

            commit_calls: list = []

            def fake_commit(writes, project, database, token=None):
                commit_calls.append(writes)
                return len(writes)

            with mock.patch.object(self.upload_pack, "commit_writes", side_effect=fake_commit), mock.patch.object(
                self.upload_pack, "_load_firebase_cli_access_token", return_value="fake-token"
            ):
                code = self.upload_pack.main(
                    ["--pack", str(pack_path), "--published-dir", str(published_dir)]
                )

            self.assertEqual(code, 0)
            self.assertGreaterEqual(len(commit_calls), 2)
            # Every chunk commit (all but the last two: meta, then prune) precedes the meta commit.
            meta_call_index = next(
                i for i, writes in enumerate(commit_calls) if writes and "updateMask" in writes[0]
            )
            for i in range(meta_call_index):
                self.assertNotIn("updateMask", commit_calls[i][0])

            published_file = published_dir / f"pack_v{old_version + 1}.json"
            self.assertTrue(published_file.exists())
            self.assertEqual(published_file.read_bytes(), new_bytes)

            prune_calls = [writes for writes in commit_calls if writes and "delete" in writes[0]]
            self.assertEqual(len(prune_calls), 1)

    def test_build_prune_writes_keeps_last_n_versions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            published_dir = Path(tmp)
            base_pack = json.loads(self.bundled_text)
            for v in range(1, 6):
                pack = dict(base_pack)
                pack["version"] = v
                (published_dir / f"pack_v{v}.json").write_text(
                    json.dumps(pack, sort_keys=True, separators=(",", ":"), ensure_ascii=False), encoding="utf-8"
                )

            writes = self.upload_pack.build_prune_writes(published_dir, 1, "projects/p/databases/(default)/documents", keep=3)
            deleted_names = {w["delete"] for w in writes}

            for v in (3, 4, 5):
                self.assertFalse(any(f"_{v}_" in name for name in deleted_names), f"v{v} should be kept")
            for v in (1, 2):
                self.assertTrue(any(f"_{v}_" in name for name in deleted_names), f"v{v} should be pruned")


if __name__ == "__main__":
    unittest.main()
