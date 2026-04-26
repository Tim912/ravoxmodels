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
- Core plugin bootstrap and command stubs
- Optional bridge module bootstrap
