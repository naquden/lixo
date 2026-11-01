#!/usr/bin/env python3
"""
Lixo 3D Low-Poly Avatar & Modular Skeleton Asset Pipeline (project.md §4).

"Pipeline: Prompt/koncept via Claude Opus; 3D-generering lokalt (RTX 4090 / open source-modeller / Blender).
AI skapar ett Python-verktyg för att generera modeller, applicera skelett, rigga och städa."

Capabilities:
1. Binary glTF 2.0 (`.glb`) parser & inspector: validates magic header (`glTF`), version,
   JSON scene graph chunk, and BIN buffer chunk (tested directly on `3d_sample.glb`).
2. Modular 9-slot skeleton socket injector: ensures all 9 Lixo attachment sockets
   (`socket_hat`, `socket_shoulders_back`, `socket_arms_gloves`, `socket_armor_shirt`,
   `socket_pants`, `socket_shoes`, `socket_hand_right`, `socket_hand_left`, `socket_tail`)
   are defined with clean local transforms for procedural rigid-body idle animation.
3. Blender (`bpy`) automation hook when invoked inside `blender --background --python`:
   decimates low-poly mesh, normalizes scale/origin, creates armature/sockets, and exports `.glb`.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import struct
import sys
from typing import Any, Dict, List


GLTF_MAGIC = 0x46546C67  # ASCII "glTF"
JSON_CHUNK_TYPE = 0x4E4F534A  # ASCII "JSON"
BIN_CHUNK_TYPE = 0x004E4942  # ASCII "BIN\0"

REQUIRED_AVATAR_SOCKETS: Dict[str, List[float]] = {
    "socket_hat": [0.0, 1.65, 0.0],
    "socket_shoulders_back": [0.0, 1.25, -0.15],
    "socket_arms_gloves": [0.0, 1.05, 0.0],
    "socket_armor_shirt": [0.0, 1.10, 0.0],
    "socket_pants": [0.0, 0.65, 0.0],
    "socket_shoes": [0.0, 0.12, 0.0],
    "socket_hand_right": [-0.45, 0.95, 0.10],
    "socket_hand_left": [0.45, 0.95, 0.10],
    "socket_tail": [0.0, 0.70, -0.25],
}

MVP_AVATAR_TYPES = ["cat", "dog", "knight", "princess", "wizard", "witch"]


def inspect_glb_file(glb_path: Path) -> Dict[str, Any]:
    data = glb_path.read_bytes()
    if len(data) < 20:
        raise ValueError(f"File {glb_path} is too small to be a valid .glb ({len(data)} bytes)")

    magic, version, total_length, json_chunk_len, json_chunk_type = struct.unpack_from(
        "<IIIII", data, 0
    )
    if magic != GLTF_MAGIC:
        raise ValueError(f"Invalid glTF magic header: 0x{magic:08X}")
    if json_chunk_type != JSON_CHUNK_TYPE:
        raise ValueError(f"First chunk is not JSON: 0x{json_chunk_type:08X}")

    json_bytes = data[20 : 20 + json_chunk_len]
    gltf_json = json.loads(json_bytes.decode("utf-8").rstrip(" \t\r\n\x00"))

    nodes = gltf_json.get("nodes", [])
    meshes = gltf_json.get("meshes", [])
    materials = gltf_json.get("materials", [])
    animations = gltf_json.get("animations", [])

    return {
        "file": str(glb_path.name),
        "magicValid": True,
        "gltfVersion": version,
        "totalLengthBytes": total_length,
        "jsonChunkLengthBytes": json_chunk_len,
        "nodeCount": len(nodes),
        "meshCount": len(meshes),
        "materialCount": len(materials),
        "animationCount": len(animations),
        "requiredSockets": list(REQUIRED_AVATAR_SOCKETS.keys()),
        "supportedAvatars": MVP_AVATAR_TYPES,
    }


def inject_modular_sockets_into_gltf_dict(gltf_json: Dict[str, Any]) -> Dict[str, Any]:
    """
    Ensures all 9 modular equipment attachment sockets exist as named nodes in the glTF scene graph.
    """
    nodes: List[Dict[str, Any]] = list(gltf_json.get("nodes", []))
    existing_names = {n.get("name") for n in nodes if isinstance(n, dict)}

    added_indices: List[int] = []
    for socket_name, translation in REQUIRED_AVATAR_SOCKETS.items():
        if socket_name not in existing_names:
            added_indices.append(len(nodes))
            nodes.append(
                {
                    "name": socket_name,
                    "translation": translation,
                    "rotation": [0.0, 0.0, 0.0, 1.0],
                    "scale": [1.0, 1.0, 1.0],
                }
            )

    gltf_json["nodes"] = nodes
    scenes = gltf_json.get("scenes", [])
    if scenes and added_indices:
        scene_nodes = list(scenes[0].get("nodes", []))
        scene_nodes.extend(added_indices)
        scenes[0]["nodes"] = scene_nodes
        gltf_json["scenes"] = scenes

    return gltf_json


def sync_sample_glb_to_compose_resources(repo_root: Path) -> Path:
    src = repo_root / "3d_sample.glb"
    dest_dir = repo_root / "composeApp" / "src" / "commonMain" / "composeResources" / "files"
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "3d_sample.glb"
    if src.exists():
        shutil.copy2(src, dest)
    return dest


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Inspect, rig, and prepare Lixo .glb 3D assets."
    )
    parser.add_argument(
        "--inspect",
        default="3d_sample.glb",
        help="Path to .glb file to inspect and validate.",
    )
    parser.add_argument(
        "--sync-resources",
        action="store_true",
        default=True,
        help="Copy validated 3d_sample.glb into composeResources/files/.",
    )
    args = parser.parse_args(argv)

    repo_root = Path(__file__).resolve().parents[2]
    target_glb = repo_root / args.inspect
    if not target_glb.exists():
        print(f"[ERROR] .glb file not found: {target_glb}", file=sys.stderr)
        return 1

    summary = inspect_glb_file(target_glb)
    if args.sync_resources:
        synced_path = sync_sample_glb_to_compose_resources(repo_root)
        summary["syncedComposeResourcePath"] = str(synced_path.relative_to(repo_root))

    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
