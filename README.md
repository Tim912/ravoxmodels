# RavoxModels

Version: `26.1`

Standalone Paper plugin for model import/runtime orchestration with API and optional `customoreplugin` bridge.

## Modules

- `ravoxmodels-api`: Public API used by other plugins.
- `ravoxmodels-core`: Main runtime/import/resourcepack/license plugin.
- `ravoxmodels-bridge-customore`: Optional HP-phase integration example.

## Build

Requires `Java 25` (for Paper `26.1.x` API).

```bash
mvn -q clean package
```

Release bundle (jars + example configs + zip):

```powershell
./tools/build-release.ps1 -Version 26.1
```

JAR outputs:

- `ravoxmodels-core/target/ravoxmodels-core-26.1.jar`
- `ravoxmodels-api/target/ravoxmodels-api-26.1.jar`
- `ravoxmodels-bridge-customore/target/ravoxmodels-bridge-customore-26.1.jar`

Server deployment:

- Put only these into `plugins/`:
- `ravoxmodels-core-26.1.jar`
- `ravoxmodels-bridge-customore-26.1.jar` (optional)
- Do **not** load `ravoxmodels-api-26.1.jar` as plugin jar; it is SDK-only for developers.
- Do not use PlugMan reload/load for RavoxModels; restart Paper.
- Do not keep `resourcepack.host.public_host: 127.0.0.1` for public servers.
- Use a reachable domain/IP via `resourcepack.host.public_host` or set `resourcepack.hosted_url`.

## Core features (`ravoxmodels-core`)

- GLB/FBX import queue from import folder
- optional import folder watcher (`ENTRY_CREATE`)
- GLB inspection:
- header/version validation
- triangle estimate
- skin bone count
- texture-size checks
- animation name extraction
- preview texture extraction
- FBX inspection:
- header/basic validation
- version warning and converter-stage note
- converter backend flow:
- command backend with per-format commands (`glb`/`fbx`)
- bundled reference backend script auto-installed to `plugins/RavoxModels/tools`
- optional Blender bridge script for FBX/GLTF normalization
- timeout + strict exit code
- optional `conversion-report.json` contract
- fallback to metadata import when strict mode is disabled
- persistent model registry (`plugins/RavoxModels/models/index.json`)
- per-model package directory with `manifest.json`
- runtime model handles with:
- spawn/despawn
- animation play
- transition blend tracking
- state tagging
- auto resourcepack generation from imported models
- embedded HTTP pack host (optional)
- force resourcepack support
- license check workflow:
- startup check
- heartbeat
- grace period
- cache file fallback

## Commands (`/ravoxmodels`)

- `help`
- `status`
- `import <filename.glb|filename.fbx>`
- `import-history [count]`
- `models`
- `model info <modelId>`
- `spawn <modelId> [player]`
- `play <handleUuid> <animationKey> [loop]`
- `transition <handleUuid> <fromKey> <toKey> <blendMs> [loop]`
- `state <handleUuid> <state>`
- `despawn <handleUuid>`
- `kill <handleUuid|*>`
- `list`
- `pack build`
- `pack info`
- `pack force <player|*>`
- `license status`
- `license refresh`

Default import folder: `plugins/RavoxModels/import`

## API quick example

```java
RavoxModelsApi api = Bukkit.getServicesManager()
        .getRegistration(RavoxModelsApi.class)
        .getProvider();

ModelHandle handle = api.spawnModel("frostfire_colossus", bossLocation);
api.playAnimation(handle, "rvxmodels.firecoloss.idle", true);

if (bossCurrentHp < 1_000_000) {
    api.transitionAnimation(
            handle,
            "rvxmodels.firecoloss.idle",
            "rvxmodels.firecoloss.attack",
            350,
            true
    );
}
```

All animation keys are normalized to `rvxmodels.*`.

## Converter backend contract

If `converter.command.enabled: true`, RavoxModels executes your command from `config.yml`.

Default config already points to bundled script:

- `py -3 {plugin_dir}/tools/converter_backend.py ...`

For real GLB/FBX conversion, install Blender and either:

- put `blender` in PATH, or
- set environment variable `RAVOX_BLENDER` to blender executable path
- on Windows, the bundled converter also searches `C:\Program Files\Blender Foundation\Blender*\blender.exe`

The bundled Blender bridge writes vanilla Minecraft resourcepack assets into:

- `{runtime_dir}/resourcepack/assets/<namespace>/models/item/<model_id>.json`
- `{runtime_dir}/resourcepack/assets/<namespace>/items/<model_id>.json`
- `{runtime_dir}/resourcepack/assets/<namespace>/textures/item/<model_id>_palette.png`

Vanilla Minecraft does not support arbitrary triangle meshes as item models. The bundled backend converts GLB/FBX surfaces into a bounded cuboid approximation so the model is visible without a client mod. Skeletal animation names and timing metadata are preserved, but full bone animation playback needs a dedicated runtime display-entity bone pipeline.

Supported placeholders:

- `{input}`
- `{output}`
- `{model_id}`
- `{model_dir}`
- `{plugin_dir}`
- `{runtime_dir}`
- `{format}`
- `{namespace}`

Optional output file (recommended): `{runtime_dir}/conversion-report.json`

Example:

```json
{
  "success": true,
  "message": "minecraft_resourcepack_assets_generated",
  "animations": ["idle", "attack"],
  "artifacts": ["runtime/resourcepack/assets/rvxmodels/models/item/example.json"],
  "warnings": []
}
```

## Bridge module (`ravoxmodels-bridge-customore`)

`/rvxbridge phase <handleUuid> <currentHp> <maxHp>`

Uses threshold mapping from bridge `config.yml` to trigger animation changes by HP ratio (80/60/40/20 example included).

## Notes

- This `26.1` codebase now includes a real Blender-backed converter flow (normalized GLB + generated vanilla resourcepack assets + report).
- Best results still depend on source-asset quality and explicit import limits (triangles/bones/textures).
