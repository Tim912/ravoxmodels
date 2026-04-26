#!/usr/bin/env python3
"""
Runs inside Blender to import GLB/FBX and emit Minecraft resourcepack assets.

Minecraft vanilla item models cannot render arbitrary triangle meshes directly.
This bridge turns the mesh surface into a bounded cuboid approximation so the
result is visible in vanilla clients without a client mod.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

import bpy
from mathutils import Vector


def parse_args() -> argparse.Namespace:
    if "--" in sys.argv:
        argv = sys.argv[sys.argv.index("--") + 1 :]
    else:
        argv = []
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--resourcepack", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--namespace", default="rvxmodels")
    parser.add_argument("--max-elements", type=int, default=1024)
    parser.add_argument("--voxel-grid", type=int, default=28)
    parser.add_argument("--palette-size", type=int, default=16)
    return parser.parse_args(argv)


def clear_scene() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for collection in (bpy.data.meshes, bpy.data.materials, bpy.data.armatures, bpy.data.images):
        for block in list(collection):
            if block.users == 0:
                collection.remove(block)


def import_any(path: Path) -> None:
    suffix = path.suffix.lower()
    if suffix == ".fbx":
        bpy.ops.import_scene.fbx(filepath=str(path), automatic_bone_orientation=True, use_anim=True)
        return
    if suffix in (".glb", ".gltf"):
        bpy.ops.import_scene.gltf(filepath=str(path))
        return
    raise RuntimeError(f"Unsupported import suffix: {suffix}")


def export_glb(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(path),
        export_format="GLB",
        export_apply=True,
        export_yup=True,
        export_texcoords=True,
        export_normals=True,
        export_tangents=True,
        export_materials="EXPORT",
        export_skins=True,
        export_animations=True,
        export_lights=False,
        export_cameras=False,
    )


def sanitize_resource_name(value: str) -> str:
    out = []
    for char in value.lower():
        if char.isalnum() or char in ("_", "-", "."):
            out.append(char)
        else:
            out.append("_")
    return "".join(out) or "model"


def mesh_objects() -> list[bpy.types.Object]:
    return [
        obj for obj in bpy.context.scene.objects
        if obj.type == "MESH" and obj.visible_get()
    ]


def to_model_axes(world: Vector) -> Vector:
    return Vector((world.x, world.z, -world.y))


def collect_bounds(objects: list[bpy.types.Object]) -> tuple[Vector, Vector]:
    depsgraph = bpy.context.evaluated_depsgraph_get()
    minimum = Vector((math.inf, math.inf, math.inf))
    maximum = Vector((-math.inf, -math.inf, -math.inf))
    found = False

    for obj in objects:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh()
        try:
            for vertex in mesh.vertices:
                point = to_model_axes(obj.matrix_world @ vertex.co)
                minimum.x = min(minimum.x, point.x)
                minimum.y = min(minimum.y, point.y)
                minimum.z = min(minimum.z, point.z)
                maximum.x = max(maximum.x, point.x)
                maximum.y = max(maximum.y, point.y)
                maximum.z = max(maximum.z, point.z)
                found = True
        finally:
            evaluated.to_mesh_clear()

    if not found:
        return Vector((-0.5, -0.5, -0.5)), Vector((0.5, 0.5, 0.5))
    return minimum, maximum


def normalize_point(point: Vector, minimum: Vector, maximum: Vector) -> Vector:
    center = (minimum + maximum) * 0.5
    extent = maximum - minimum
    max_extent = max(extent.x, extent.y, extent.z, 0.001)
    scale = 14.0 / max_extent
    normalized = (point - center) * scale + Vector((8.0, 8.0, 8.0))
    return Vector((
        min(15.999, max(0.001, normalized.x)),
        min(15.999, max(0.001, normalized.y)),
        min(15.999, max(0.001, normalized.z)),
    ))


def material_image(material: bpy.types.Material | None) -> bpy.types.Image | None:
    if material is None or not material.use_nodes or material.node_tree is None:
        return None
    for node in material.node_tree.nodes:
        if node.bl_idname == "ShaderNodeTexImage" and getattr(node, "image", None) is not None:
            return node.image
    return None


def material_base_color(material: bpy.types.Material | None) -> tuple[float, float, float, float]:
    if material is not None:
        color = getattr(material, "diffuse_color", None)
        if color is not None and len(color) >= 4:
            return float(color[0]), float(color[1]), float(color[2]), float(color[3])
    return 0.72, 0.72, 0.72, 1.0


def sample_image(image: bpy.types.Image, uv: Vector) -> tuple[float, float, float, float] | None:
    width, height = int(image.size[0]), int(image.size[1])
    if width <= 0 or height <= 0:
        return None
    try:
        pixels = image.pixels
        u = uv.x % 1.0
        v = uv.y % 1.0
        x = min(width - 1, max(0, int(u * width)))
        y = min(height - 1, max(0, int((1.0 - v) * height)))
        index = (y * width + x) * 4
        return float(pixels[index]), float(pixels[index + 1]), float(pixels[index + 2]), float(pixels[index + 3])
    except Exception:
        return None


def polygon_color(obj: bpy.types.Object, mesh: bpy.types.Mesh, polygon: bpy.types.MeshPolygon) -> tuple[float, float, float, float]:
    material = None
    if 0 <= polygon.material_index < len(obj.material_slots):
        material = obj.material_slots[polygon.material_index].material

    color = material_base_color(material)
    image = material_image(material)
    uv_layer = mesh.uv_layers.active
    if image is None or uv_layer is None:
        return color

    uv = Vector((0.0, 0.0))
    loop_count = max(1, len(polygon.loop_indices))
    for loop_index in polygon.loop_indices:
        uv += uv_layer.data[loop_index].uv
    uv *= 1.0 / loop_count

    sampled = sample_image(image, uv)
    if sampled is None or sampled[3] <= 0.02:
        return color
    return sampled


def collect_surface_samples(objects: list[bpy.types.Object], max_elements: int) -> list[tuple[Vector, tuple[float, float, float, float]]]:
    depsgraph = bpy.context.evaluated_depsgraph_get()
    total_polygons = 0
    for obj in objects:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh()
        try:
            total_polygons += len(mesh.polygons)
        finally:
            evaluated.to_mesh_clear()

    target_samples = max(512, max_elements * 6)
    step = max(1, total_polygons // target_samples)
    samples: list[tuple[Vector, tuple[float, float, float, float]]] = []
    polygon_index = 0

    for obj in objects:
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh()
        try:
            for polygon in mesh.polygons:
                polygon_index += 1
                if polygon_index % step != 0:
                    continue
                color = polygon_color(obj, mesh, polygon)
                center = obj.matrix_world @ polygon.center
                samples.append((to_model_axes(center), color))

                if len(polygon.vertices) <= 4:
                    for vertex_index in polygon.vertices:
                        samples.append((to_model_axes(obj.matrix_world @ mesh.vertices[vertex_index].co), color))
        finally:
            evaluated.to_mesh_clear()

    return samples


def build_cells(
    samples: list[tuple[Vector, tuple[float, float, float, float]]],
    minimum: Vector,
    maximum: Vector,
    voxel_grid: int,
    max_elements: int,
) -> tuple[int, dict[tuple[int, int, int], dict[str, Any]]]:
    if not samples:
        return 1, {(0, 0, 0): {"color": [0.75, 0.75, 0.75, 1.0], "count": 1}}

    for grid in range(max(4, voxel_grid), 3, -2):
        cells: dict[tuple[int, int, int], dict[str, Any]] = {}
        for point, color in samples:
            normalized = normalize_point(point, minimum, maximum)
            key = (
                min(grid - 1, max(0, int((normalized.x / 16.0) * grid))),
                min(grid - 1, max(0, int((normalized.y / 16.0) * grid))),
                min(grid - 1, max(0, int((normalized.z / 16.0) * grid))),
            )
            cell = cells.setdefault(key, {"color": [0.0, 0.0, 0.0, 0.0], "count": 0})
            cell["count"] += 1
            for channel in range(4):
                cell["color"][channel] += color[channel]
        if len(cells) <= max_elements or grid <= 4:
            if len(cells) > max_elements:
                selected = sorted(cells.items(), key=lambda item: item[1]["count"], reverse=True)[:max_elements]
                cells = dict(selected)
            return grid, cells

    raise RuntimeError("Unable to build voxel cells")


def color_key(color: list[float], count: int) -> tuple[int, int, int, int]:
    if count <= 0:
        count = 1
    rgba = [min(255, max(0, int(round((color[i] / count) * 255.0)))) for i in range(4)]
    return rgba[0] // 8 * 8, rgba[1] // 8 * 8, rgba[2] // 8 * 8, max(255, rgba[3])


def nearest_palette_index(palette: list[tuple[int, int, int, int]], key: tuple[int, int, int, int]) -> int:
    best_index = 0
    best_distance = float("inf")
    for index, color in enumerate(palette):
        distance = sum((int(color[i]) - int(key[i])) ** 2 for i in range(3))
        if distance < best_distance:
            best_distance = distance
            best_index = index
    return best_index


def assign_palette(cells: dict[tuple[int, int, int], dict[str, Any]], palette_size: int) -> list[tuple[int, int, int, int]]:
    capacity = palette_size * palette_size
    palette: list[tuple[int, int, int, int]] = []
    lookup: dict[tuple[int, int, int, int], int] = {}

    for cell in cells.values():
        key = color_key(cell["color"], int(cell["count"]))
        if key in lookup:
            cell["palette"] = lookup[key]
            continue
        if len(palette) < capacity:
            lookup[key] = len(palette)
            cell["palette"] = len(palette)
            palette.append(key)
            continue
        cell["palette"] = nearest_palette_index(palette, key)

    if not palette:
        palette.append((190, 190, 190, 255))
    return palette


def save_palette_texture(path: Path, palette: list[tuple[int, int, int, int]], palette_size: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image = bpy.data.images.new(path.stem, width=palette_size, height=palette_size, alpha=True)
    pixels = [0.0] * (palette_size * palette_size * 4)

    for index, color in enumerate(palette):
        x = index % palette_size
        y = index // palette_size
        pixel_y = palette_size - 1 - y
        offset = (pixel_y * palette_size + x) * 4
        pixels[offset] = color[0] / 255.0
        pixels[offset + 1] = color[1] / 255.0
        pixels[offset + 2] = color[2] / 255.0
        pixels[offset + 3] = color[3] / 255.0

    image.pixels.foreach_set(pixels)
    image.filepath_raw = str(path)
    image.file_format = "PNG"
    image.save()


def uv_for_palette(index: int, palette_size: int) -> list[float]:
    x = index % palette_size
    y = index // palette_size
    return [float(x), float(y), float(x + 1), float(y + 1)]


def build_elements(cells: dict[tuple[int, int, int], dict[str, Any]], grid: int, palette_size: int) -> list[dict[str, Any]]:
    elements: list[dict[str, Any]] = []
    unit = 16.0 / float(grid)

    for key, cell in sorted(cells.items()):
        x, y, z = key
        start = [round(x * unit, 4), round(y * unit, 4), round(z * unit, 4)]
        end = [round((x + 1) * unit, 4), round((y + 1) * unit, 4), round((z + 1) * unit, 4)]
        uv = uv_for_palette(int(cell.get("palette", 0)), palette_size)
        face = {"uv": uv, "texture": "#palette"}
        elements.append({
            "from": start,
            "to": end,
            "shade": True,
            "faces": {
                "north": dict(face),
                "south": dict(face),
                "east": dict(face),
                "west": dict(face),
                "up": dict(face),
                "down": dict(face),
            },
        })
    return elements


def animation_manifest() -> list[dict[str, Any]]:
    animations = []
    fps = float(bpy.context.scene.render.fps or 20)
    for action in bpy.data.actions:
        start, end = action.frame_range
        animations.append({
            "name": action.name,
            "start_frame": float(start),
            "end_frame": float(end),
            "duration_seconds": max(0.0, (float(end) - float(start)) / fps),
        })
    return animations


def write_resourcepack(resourcepack_dir: Path, namespace: str, model_id: str, elements: list[dict[str, Any]], palette_size: int, palette: list[tuple[int, int, int, int]]) -> None:
    model_dir = resourcepack_dir / "assets" / namespace / "models" / "item"
    item_dir = resourcepack_dir / "assets" / namespace / "items"
    texture_dir = resourcepack_dir / "assets" / namespace / "textures" / "item"
    model_dir.mkdir(parents=True, exist_ok=True)
    item_dir.mkdir(parents=True, exist_ok=True)
    texture_dir.mkdir(parents=True, exist_ok=True)

    texture_name = f"{model_id}_palette"
    save_palette_texture(texture_dir / f"{texture_name}.png", palette, palette_size)

    model_json = {
        "credit": "Generated by RavoxModels Blender converter",
        "textures": {
            "palette": f"{namespace}:item/{texture_name}",
        },
        "elements": elements,
        "display": {
            "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [2.0, 2.0, 2.0]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [1.0, 1.0, 1.0]},
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
        },
    }
    (model_dir / f"{model_id}.json").write_text(json.dumps(model_json, indent=2), encoding="utf-8")

    item_json = {
        "model": {
            "type": "minecraft:model",
            "model": f"{namespace}:item/{model_id}",
        }
    }
    (item_dir / f"{model_id}.json").write_text(json.dumps(item_json, indent=2), encoding="utf-8")


def write_runtime_manifest(path: Path, grid: int, elements: int, palette: int, animations: list[dict[str, Any]]) -> None:
    manifest = {
        "renderer": "vanilla_cuboid_approximation",
        "voxel_grid": grid,
        "element_count": elements,
        "palette_colors": palette,
        "animations": animations,
        "notes": [
            "GLB/FBX triangle meshes are approximated as Minecraft cuboids.",
            "Animation metadata is preserved; full skeletal playback requires a runtime bone/display pipeline.",
        ],
    }
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")


def main() -> int:
    args = parse_args()
    src = Path(args.input).resolve()
    out = Path(args.output).resolve()
    resourcepack_dir = Path(args.resourcepack).resolve()
    model_id = sanitize_resource_name(args.model)
    namespace = sanitize_resource_name(args.namespace)
    max_elements = max(1, int(args.max_elements))
    voxel_grid = max(4, min(64, int(args.voxel_grid)))
    palette_size = max(1, min(32, int(args.palette_size)))

    clear_scene()
    import_any(src)
    export_glb(out)

    objects = mesh_objects()
    minimum, maximum = collect_bounds(objects)
    samples = collect_surface_samples(objects, max_elements)
    grid, cells = build_cells(samples, minimum, maximum, voxel_grid, max_elements)
    palette = assign_palette(cells, palette_size)
    elements = build_elements(cells, grid, palette_size)
    animations = animation_manifest()

    write_resourcepack(resourcepack_dir, namespace, model_id, elements, palette_size, palette)
    write_runtime_manifest(out.parent / "minecraft-model-report.json", grid, len(elements), len(palette), animations)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
