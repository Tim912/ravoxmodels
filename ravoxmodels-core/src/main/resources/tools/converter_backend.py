#!/usr/bin/env python3
"""
RavoxModels converter backend (production-oriented baseline).

Features:
- Optional Blender conversion (FBX/GLTF/GLB -> normalized GLB)
- GLB deep inspection (mesh, skin, animation, material, texture metadata)
- Runtime artifact generation under output directory
- conversion-report.json contract for RavoxModels Java backend
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import platform
import shutil
import struct
import subprocess
from pathlib import Path
from typing import Any


SUPPORTED_MATERIAL_EXTS = {
    "KHR_materials_transmission",
    "KHR_materials_clearcoat",
    "KHR_materials_ior",
    "KHR_materials_specular",
    "KHR_materials_emissive_strength",
    "KHR_texture_transform",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--format", required=True)
    parser.add_argument("--namespace", default="rvxmodels")
    parser.add_argument("--blender", default="")
    parser.add_argument("--max-elements", type=int, default=1024)
    parser.add_argument("--voxel-grid", type=int, default=30)
    parser.add_argument("--palette-size", type=int, default=32)
    parser.add_argument("--model-mode", choices=("rendered_cross", "voxel"), default="rendered_cross")
    parser.add_argument("--strict", action="store_true")
    return parser.parse_args()


def read_glb(glb_path: Path) -> tuple[dict[str, Any], bytes | None]:
    raw = glb_path.read_bytes()
    if len(raw) < 20:
        raise ValueError("GLB too small")
    magic, version, declared_len = struct.unpack_from("<4sII", raw, 0)
    if magic != b"glTF":
        raise ValueError("Invalid GLB magic")
    if version != 2:
        raise ValueError(f"Unsupported GLB version {version}")
    if declared_len != len(raw):
        raise ValueError("GLB length mismatch")

    json_chunk = None
    bin_chunk = None
    offset = 12
    while offset + 8 <= len(raw):
        chunk_len, chunk_type = struct.unpack_from("<II", raw, offset)
        offset += 8
        if offset + chunk_len > len(raw):
            raise ValueError("Invalid GLB chunk layout")
        data = raw[offset : offset + chunk_len]
        offset += chunk_len
        if chunk_type == 0x4E4F534A:
            json_chunk = data
        elif chunk_type == 0x004E4942 and bin_chunk is None:
            bin_chunk = data
    if json_chunk is None:
        raise ValueError("Missing GLB JSON chunk")
    return json.loads(json_chunk.decode("utf-8").rstrip("\x00\r\n\t ")), bin_chunk


def read_jpeg_size(data: bytes) -> tuple[int, int] | None:
    if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8:
        return None
    i = 2
    while i + 1 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        i += 2
        if marker in (0xD8, 0xD9):
            continue
        if i + 2 > len(data):
            return None
        seg_len = int.from_bytes(data[i : i + 2], "big")
        if seg_len < 2 or i + seg_len > len(data):
            return None
        if marker in (0xC0, 0xC1, 0xC2, 0xC3):
            if i + 7 > len(data):
                return None
            h = int.from_bytes(data[i + 3 : i + 5], "big")
            w = int.from_bytes(data[i + 5 : i + 7], "big")
            return w, h
        i += seg_len
    return None


def read_png_size(data: bytes) -> tuple[int, int] | None:
    if len(data) < 24:
        return None
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    if data[12:16] != b"IHDR":
        return None
    w = int.from_bytes(data[16:20], "big")
    h = int.from_bytes(data[20:24], "big")
    return w, h


def image_bytes(image: dict[str, Any], base_dir: Path, buffer_views: list[dict[str, Any]], bin_chunk: bytes | None) -> bytes | None:
    uri = image.get("uri")
    if isinstance(uri, str):
        if uri.startswith("data:"):
            comma = uri.find(",")
            if comma >= 0:
                return base64.b64decode(uri[comma + 1 :])
            return None
        external = (base_dir / uri).resolve()
        if external.exists():
            return external.read_bytes()
        return None

    if "bufferView" in image and bin_chunk is not None:
        idx = int(image["bufferView"])
        if idx < 0 or idx >= len(buffer_views):
            return None
        bv = buffer_views[idx]
        off = int(bv.get("byteOffset", 0))
        ln = int(bv.get("byteLength", 0))
        if off < 0 or ln <= 0 or off + ln > len(bin_chunk):
            return None
        return bin_chunk[off : off + ln]
    return None


def inspect_glb(glb_path: Path) -> tuple[dict[str, Any], list[str]]:
    root, bin_chunk = read_glb(glb_path)
    warnings: list[str] = []

    accessors = root.get("accessors", []) or []
    meshes = root.get("meshes", []) or []
    skins = root.get("skins", []) or []
    animations = root.get("animations", []) or []
    nodes = root.get("nodes", []) or []
    materials = root.get("materials", []) or []
    images = root.get("images", []) or []
    buffer_views = root.get("bufferViews", []) or []

    triangles = 0
    vertices = 0
    max_skin_joints = 0
    max_texture_size = 0
    texture_count = 0
    morph_target_primitives = 0

    for skin in skins:
        joints = skin.get("joints", []) or []
        max_skin_joints = max(max_skin_joints, len(joints))

    for mesh in meshes:
        for primitive in mesh.get("primitives", []) or []:
            mode = int(primitive.get("mode", 4))
            if mode != 4:
                warnings.append(f"Non-triangle primitive mode detected: {mode}")
                continue
            attrs = primitive.get("attributes", {}) or {}
            pos_accessor = attrs.get("POSITION")
            if isinstance(pos_accessor, int) and 0 <= pos_accessor < len(accessors):
                vertices += int(accessors[pos_accessor].get("count", 0))
            idx_accessor = primitive.get("indices")
            if isinstance(idx_accessor, int) and 0 <= idx_accessor < len(accessors):
                triangles += int(accessors[idx_accessor].get("count", 0)) // 3
            elif isinstance(pos_accessor, int) and 0 <= pos_accessor < len(accessors):
                triangles += int(accessors[pos_accessor].get("count", 0)) // 3

            has_joint = "JOINTS_0" in attrs
            has_weight = "WEIGHTS_0" in attrs
            if has_joint != has_weight:
                warnings.append("Primitive has JOINTS without WEIGHTS or vice versa.")
            if primitive.get("targets"):
                morph_target_primitives += 1

    anim_names: list[str] = []
    for i, anim in enumerate(animations):
        name = anim.get("name")
        if not name:
            name = f"animation_{i + 1}"
        anim_names.append(str(name))

    unsupported_exts: list[str] = []
    for ext in root.get("extensionsUsed", []) or []:
        if ext.startswith("KHR_materials_") and ext not in SUPPORTED_MATERIAL_EXTS:
            unsupported_exts.append(ext)
    if unsupported_exts:
        warnings.append("Unsupported material extensions: " + ", ".join(sorted(set(unsupported_exts))))
    if morph_target_primitives > 0:
        warnings.append(f"Morph targets detected on {morph_target_primitives} primitive(s).")

    for image in images:
        payload = image_bytes(image, glb_path.parent, buffer_views, bin_chunk)
        if not payload:
            continue
        texture_count += 1
        size = read_png_size(payload) or read_jpeg_size(payload)
        if size:
            max_texture_size = max(max_texture_size, size[0], size[1])
        else:
            warnings.append("Texture found but dimensions could not be parsed.")

    material_names: list[str] = []
    for i, material in enumerate(materials):
        material_names.append(str(material.get("name") or f"material_{i + 1}"))

    stats = {
        "mesh_count": len(meshes),
        "node_count": len(nodes),
        "material_count": len(materials),
        "texture_count": texture_count,
        "animation_count": len(anim_names),
        "triangles_estimated": triangles,
        "vertices_estimated": vertices,
        "max_skin_joints": max_skin_joints,
        "max_texture_size": max_texture_size,
        "animation_names": anim_names,
        "material_names": material_names,
    }
    return stats, warnings


def run_blender(
    input_path: Path,
    output_glb: Path,
    resourcepack_dir: Path,
    blender: str,
    model_id: str,
    namespace: str,
    max_elements: int,
    voxel_grid: int,
    palette_size: int,
    model_mode: str,
) -> tuple[bool, str]:
    bridge = Path(__file__).with_name("converter_blender_bridge.py")
    if not bridge.exists():
        return False, "converter_blender_bridge.py missing"
    cmd = [
        blender,
        "--background",
        "--factory-startup",
        "--python",
        str(bridge),
        "--",
        "--input",
        str(input_path),
        "--output",
        str(output_glb),
        "--resourcepack",
        str(resourcepack_dir),
        "--model",
        model_id,
        "--namespace",
        namespace,
        "--max-elements",
        str(max_elements),
        "--voxel-grid",
        str(voxel_grid),
        "--palette-size",
        str(palette_size),
        "--model-mode",
        model_mode,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    output = (proc.stdout or "") + "\n" + (proc.stderr or "")
    if proc.returncode != 0:
        return False, f"Blender conversion failed: {safe_short(output)}"
    return True, safe_short(output)


def find_blender(explicit: str) -> str | None:
    if explicit:
        p = Path(explicit)
        if p.exists():
            return str(p)
    env = os.environ.get("RAVOX_BLENDER", "").strip()
    if env:
        p = Path(env)
        if p.exists():
            return str(p)
    found = shutil.which("blender")
    if found:
        return found
    if platform.system().lower() == "windows":
        roots = [
            os.environ.get("ProgramFiles", r"C:\Program Files"),
            os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
            os.environ.get("LOCALAPPDATA", ""),
        ]
        candidates: list[Path] = []
        for root in roots:
            if not root:
                continue
            base = Path(root)
            candidates.extend(base.glob("Blender Foundation/Blender*/blender.exe"))
            candidates.extend(base.glob("Programs/Blender Foundation/Blender*/blender.exe"))
        existing = sorted((p for p in candidates if p.exists()), reverse=True)
        if existing:
            return str(existing[0])
    return None


def list_artifacts(out_dir: Path) -> list[str]:
    artifacts: list[str] = []
    if not out_dir.exists():
        return artifacts
    for path in sorted(out_dir.rglob("*")):
        if path.is_file():
            artifacts.append(path.relative_to(out_dir.parent).as_posix())
    return artifacts


def safe_short(text: str) -> str:
    t = text.replace("\r", " ").replace("\n", " ").strip()
    if len(t) <= 400:
        return t
    return t[:400] + "..."


def has_generated_pack_assets(resourcepack_dir: Path, namespace: str, model_id: str) -> bool:
    model_json = resourcepack_dir / "assets" / namespace / "models" / "item" / f"{model_id}.json"
    item_json = resourcepack_dir / "assets" / namespace / "items" / f"{model_id}.json"
    texture_dir = resourcepack_dir / "assets" / namespace / "textures" / "item"
    if not model_json.exists() or not item_json.exists() or not texture_dir.exists():
        return False
    prefix = f"{model_id}_"
    for candidate in texture_dir.glob("*.png"):
        if candidate.name.startswith(prefix) or candidate.stem == model_id:
            return True
    return False


def main() -> int:
    args = parse_args()
    src = Path(args.input).resolve()
    out_dir = Path(args.output).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    fmt = args.format.lower()
    warnings: list[str] = []

    normalized_glb = out_dir / "normalized.glb"
    resourcepack_dir = out_dir / "resourcepack"
    try:
        blender_bin = find_blender(args.blender)
        if not blender_bin:
            message = "No Blender binary found. Install Blender, put blender.exe in PATH, or set RAVOX_BLENDER."
            if fmt == "glb" and src.suffix.lower() == ".glb" and not args.strict:
                shutil.copy2(src, normalized_glb)
            report = {
                "success": False if args.strict else True,
                "message": message,
                "animations": [],
                "artifacts": list_artifacts(out_dir),
                "warnings": [message, "No Minecraft model assets were generated."],
            }
            (out_dir / "conversion-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            return 1 if args.strict else 0

        ok, blender_msg = run_blender(
            src,
            normalized_glb,
            resourcepack_dir,
            blender_bin,
            args.model,
            args.namespace,
            args.max_elements,
            args.voxel_grid,
            args.palette_size,
            args.model_mode,
        )
        if not ok:
            report = {
                "success": False if args.strict else True,
                "message": blender_msg,
                "animations": [],
                "artifacts": list_artifacts(out_dir),
                "warnings": [blender_msg, "No Minecraft model assets were generated."],
            }
            (out_dir / "conversion-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
            return 1 if args.strict else 0
        warnings.append("Blender: " + blender_msg)
    except Exception as exc:
        report = {
            "success": False,
            "message": f"Converter error: {exc}",
            "animations": [],
            "artifacts": [],
            "warnings": [],
        }
        (out_dir / "conversion-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        return 1

    pack_assets_ok = has_generated_pack_assets(resourcepack_dir, args.namespace, args.model)
    if not pack_assets_ok:
        report = {
            "success": False if args.strict else True,
            "message": "Blender finished but no resourcepack assets were generated.",
            "animations": [],
            "artifacts": list_artifacts(out_dir),
            "warnings": warnings + ["Missing generated model/item/texture files in runtime/resourcepack."],
        }
        (out_dir / "conversion-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        return 1 if args.strict else 0

    try:
        stats, inspect_warnings = inspect_glb(normalized_glb)
        warnings.extend(inspect_warnings)
    except Exception as exc:
        warnings.append("Post-inspection failed: " + safe_short(str(exc)))
        stats = {
            "mesh_count": 0,
            "node_count": 0,
            "material_count": 0,
            "texture_count": 0,
            "animation_count": 0,
            "triangles_estimated": 0,
            "vertices_estimated": 0,
            "max_skin_joints": 0,
            "max_texture_size": 0,
            "animation_names": [],
            "material_names": [],
        }

    stats_path = out_dir / "model_stats.json"
    stats_path.write_text(json.dumps(stats, indent=2), encoding="utf-8")

    skeleton = {
        "model_id": args.model,
        "max_skin_joints": stats["max_skin_joints"],
        "node_count": stats["node_count"],
    }
    skeleton_path = out_dir / "skeleton.json"
    skeleton_path.write_text(json.dumps(skeleton, indent=2), encoding="utf-8")

    anim_manifest = {"animations": stats["animation_names"]}
    anim_path = out_dir / "animations.json"
    anim_path.write_text(json.dumps(anim_manifest, indent=2), encoding="utf-8")
    artifacts = list_artifacts(out_dir)

    report = {
        "success": True,
        "message": "minecraft_resourcepack_assets_generated",
        "animations": stats["animation_names"],
        "artifacts": artifacts,
        "warnings": warnings,
    }
    (out_dir / "conversion-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
