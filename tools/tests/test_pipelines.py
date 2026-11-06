import json
import re
from pathlib import Path
import unittest

from tools.asset_pipeline.blender_avatar_pipeline import (
    REQUIRED_AVATAR_SOCKETS,
    inject_modular_sockets_into_gltf_dict,
    inspect_glb_file,
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


if __name__ == "__main__":
    unittest.main()
