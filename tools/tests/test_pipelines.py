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


class LixoPipelineAndSpecificationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.repo_root = Path(__file__).resolve().parents[2]

    def test_seed_bundle_passes_all_mvp_constraints(self) -> None:
        bundle = load_seed_bundle()
        self.assertEqual(len(bundle["levels"]), 100)
        self.assertEqual(len(bundle["tasks"]), 100)
        self.assertEqual([lvl["order"] for lvl in bundle["levels"]], list(range(1, 101)))
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
