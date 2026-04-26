#!/usr/bin/env python3
"""
Reference converter backend for RavoxModels.

This script is intentionally conservative: it demonstrates the command contract,
creates deterministic runtime artifacts, and writes conversion-report.json.
You can replace internals with Blender/Assimp/Godot pipeline steps later.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--format", required=True)
    args = parser.parse_args()

    src = Path(args.input).resolve()
    out_dir = Path(args.output).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    ext = src.suffix.lower().lstrip(".") or "bin"
    copied = out_dir / f"source_converted.{ext}"
    shutil.copy2(src, copied)

    runtime_manifest = {
        "model_id": args.model,
        "format": args.format,
        "source_copy": copied.name,
        "note": "Reference backend only. Replace with your real mesh/rig converter.",
    }
    (out_dir / "runtime-manifest.json").write_text(
        json.dumps(runtime_manifest, indent=2), encoding="utf-8"
    )

    report = {
        "success": True,
        "message": "reference_backend_ok",
        "animations": [],
        "artifacts": [
            f"runtime/{copied.name}",
            "runtime/runtime-manifest.json",
        ],
        "warnings": [
            "Reference backend used. Plug in your production converter here."
        ],
    }
    (out_dir / "conversion-report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
