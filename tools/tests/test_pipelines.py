import json
from pathlib import Path
import unittest

from tools.asset_pipeline.blender_avatar_pipeline import (
    REQUIRED_AVATAR_SOCKETS,
    inject_modular_sockets_into_gltf_dict,
    inspect_glb_file,
)
from tools.content_pipeline.generate_content import (
    generate_seed_bundle,
    validate_seed_bundle,
)


class LixoPipelineAndSpecificationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.repo_root = Path(__file__).resolve().parents[2]

    def test_seed_bundle_passes_all_mvp_constraints(self) -> None:
        bundle = generate_seed_bundle()
        errors = validate_seed_bundle(bundle)
        self.assertEqual(errors, [], f"Seed validation failed: {errors}")
        self.assertEqual(len(bundle["levels"]), 100)
        self.assertEqual(len(bundle["tasks"]), 100)
        self.assertGreaterEqual(len(bundle["wardrobeItems"]), 100)

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
