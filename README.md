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
- `list`
- `pack build`
- `pack info`
- `pack force <player|*>`
- `license status`
- `license refresh`

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

Supported placeholders:

- `{input}`
- `{output}`
- `{model_id}`
- `{model_dir}`
- `{plugin_dir}`
- `{runtime_dir}`
- `{format}`

Optional output file (recommended): `{runtime_dir}/conversion-report.json`

Example:

```json
{
  "success": true,
  "message": "ok",
  "animations": ["idle", "attack"],
  "artifacts": ["runtime/mesh.bin", "runtime/anim.bin"],
  "warnings": []
}
```

## Bridge module (`ravoxmodels-bridge-customore`)

`/rvxbridge phase <handleUuid> <currentHp> <maxHp>`

Uses threshold mapping from bridge `config.yml` to trigger animation changes by HP ratio (80/60/40/20 example included).

## Notes

- This `26.1` codebase provides a robust pipeline/runtime foundation.
- Fully generic, perfect conversion for arbitrary third-party GLB/FBX assets is still a bounded problem and should run under explicit asset rules (triangle/bone/texture limits).
