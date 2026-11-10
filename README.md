# klyph-physics

a small, fast 2D physics engine for [klyph](https://github.com/inemtsev/klyph) 🟢

[![CI](https://github.com/pererikbergman/klyph-physics/actions/workflows/ci.yml/badge.svg)](https://github.com/pererikbergman/klyph-physics/actions/workflows/ci.yml)

## ⚡ 10-second version

- 🍮 **soft bodies**, 🪢 **ropes**, 🧣 **cloth**, 📦 **rigid bodies**, 🔩 **joints**
- 🧮 all of it in **one XPBD solver**, so a blob can push a crate and a crate can squash a blob
- 🧩 a **side module** for klyph: the engine itself doesn't depend on anything
- 🎮 a **sandbox demo** of every feature, and later a playable level

## ▶️ Run it

```
./gradlew :demo:run     # the sandbox (an empty window, for now)
./gradlew allTests      # the engine's tests
```

You need JDK 17+ and SDL3 3.4+. On macOS and Windows the klyph plugin downloads SDL3 if it's missing.

## 🧩 What's in here

| module | what |
|---|---|
| `klyph-physics/` | 🧠 the engine. pure Kotlin, **zero dependencies** |
| `klyph-physics-bridge/` | 🔌 the klyph glue: run a world in your app, draw what the physics sees |
| `demo/` | 🎮 the sandbox app |

## 🚧 Status

Just getting started. The plan is in [the design](docs/specs/2026-11-10-klyph-physics-design.md),
and the work is tracked in the [milestones](../../milestones).

It grew out of the slime in [slimy-the-blob](https://github.com/pererikbergman/slimy-the-blob).

## 📜 Licence

Apache-2.0, the same as klyph.
