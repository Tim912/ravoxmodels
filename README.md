# RavoxModels

Version: `26.1`

Standalone Paper plugin for model runtime and animation control with public API.

## Modules

- `ravoxmodels-api`: Public interfaces for other plugins.
- `ravoxmodels-core`: Main plugin runtime (commands, import pipeline, pack flow).
- `ravoxmodels-bridge-customore`: Optional integration bridge for CustomOrePlugin.

## Build

```bash
mvn -q clean package
```

## Roadmap (v26.1)

- Project scaffold and API contract
- Runtime command surface for model control
- Resourcepack build/serve/force flow
- License framework (startup check + heartbeat + grace)
- Optional bridge module bootstrap

## Commands

- `/ravoxmodels help`
- `/ravoxmodels import <filename.glb|filename.fbx>`
- `/ravoxmodels spawn <modelId>`
- `/ravoxmodels play <handleUuid> <animationKey> [loop]`
- `/ravoxmodels transition <handleUuid> <fromKey> <toKey> <blendMs> [loop]`
- `/ravoxmodels despawn <handleUuid>`
- `/ravoxmodels list`
- `/ravoxmodels pack <build|info|force>`
- `/ravoxmodels license <status|refresh>`

Animation keys are normalized to `rvxmodels.*` by default.
