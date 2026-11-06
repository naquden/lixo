import json
import re
from pathlib import Path
import unittest

from tools.asset_pipeline.blender_avatar_pipeline import (
    REQUIRED_AVATAR_SOCKETS,
    inject_modular_sockets_into_gltf_dict,
    inspect_glb_file,
)
from tools.item_pipeline.generate_wardrobe_items import (
    RARITIES,
    SLOTS,
    generate_catalog,
    render_kotlin,
    slug_key,
)

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

    def test_3d_sample_glb_binary_inspection(self) -> None:
        glb_path = self.repo_root / "3d_sample.glb"
        self.assertTrue(glb_path.exists(), "3d_sample.glb must exist in repo root")
        info = inspect_glb_file(glb_path)
        self.assertTrue(info["magicValid"])
        self.assertEqual(info["gltfVersion"], 2)
        self.assertEqual(info["totalLengthBytes"], 960816)
        self.assertEqual(len(info["requiredSockets"]), 9)
        self.assertEqual(len(info["supportedAvatars"]), 6)

    def test_socket_injection_adds_all_nine_equipment_anchors(self) -> None:
        dummy_gltf = {"scenes": [{"nodes": [0]}], "nodes": [{"name": "root_mesh"}]}
        updated = inject_modular_sockets_into_gltf_dict(dummy_gltf)
        node_names = {n["name"] for n in updated["nodes"]}
        for socket_name in REQUIRED_AVATAR_SOCKETS:
            self.assertIn(socket_name, node_names)

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
        kotlin_a = render_kotlin(catalog_a)
        kotlin_b = render_kotlin(catalog_b)
        self.assertEqual(kotlin_a, kotlin_b)

    def test_different_seed_changes_output(self) -> None:
        catalog_a = generate_catalog(per_slot=64, seed=42)
        catalog_b = generate_catalog(per_slot=64, seed=7)
        self.assertNotEqual(render_kotlin(catalog_a), render_kotlin(catalog_b))

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
            self.assertLessEqual(thresholds[0], 10, f"slot {slot.slot_id}'s first star unlock should be low")
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
        # catalog size, so growing/shrinking --per-slot doesn't remap users' unlocked items.
        small = generate_catalog(per_slot=32, seed=42)
        large = generate_catalog(per_slot=64, seed=42)
        for slot in SLOTS:
            by_combo_small = {(i.style_name, i.colorway_name): i.item_id for i in small[slot.slot_id]}
            by_combo_large = {(i.style_name, i.colorway_name): i.item_id for i in large[slot.slot_id]}
            for combo, item_id in by_combo_small.items():
                if combo in by_combo_large:
                    self.assertEqual(item_id, by_combo_large[combo])

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

    def test_rejects_per_slot_smaller_than_rarity_count(self) -> None:
        with self.assertRaises(ValueError):
            generate_catalog(per_slot=3, seed=42)

    def test_rendered_kotlin_has_expected_structure(self) -> None:
        catalog = generate_catalog(per_slot=16, seed=42)
        kotlin_source = render_kotlin(catalog)
        self.assertIn("// GENERATED by tools/item_pipeline/generate_wardrobe_items.py", kotlin_source)
        self.assertIn("internal val GENERATED_WARDROBE_ITEMS: List<WardrobeItem> =", kotlin_source)
        for slot in SLOTS:
            first_item = catalog[slot.slot_id][0]
            style_key = slug_key(first_item.style_name)
            colorway_key = slug_key(first_item.colorway_name)
            self.assertIn(f'id = "item_{slot.slot_id}_{style_key}_{colorway_key}"', kotlin_source)


if __name__ == "__main__":
    unittest.main()
