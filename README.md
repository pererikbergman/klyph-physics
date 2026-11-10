<p align="center">
  <img src="docs/img/blob-garden.gif" alt="five soft blobs hopping around the Blob Garden" width="100%">
</p>

<p align="center">
  <b>a small, fast 2D physics engine for <a href="https://github.com/inemtsev/klyph">klyph</a></b> 🟢<br>
  <a href="https://github.com/pererikbergman/klyph-physics/actions/workflows/ci.yml"><img src="https://github.com/pererikbergman/klyph-physics/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
</p>

---

## ⚡ 10-second version

- 🍮 **soft bodies** today. 🪢 ropes, 🧣 cloth, 📦 rigid bodies and 🔩 joints are on the way
- 🧮 all of it in **one XPBD solver**, so a blob can push a crate and a crate can squash a blob
- 🧩 a **side module**: the engine itself depends on **nothing**, not even klyph
- 🎮 a **sandbox** that shows every feature, one scene at a time
- 🧪 **60 tests**, all green

## ▶️ Run it

```
git clone https://github.com/pererikbergman/klyph-physics
cd klyph-physics
./gradlew :demo:run
```

You need JDK 17+. On macOS and Windows the klyph plugin downloads SDL3 for you. On Linux, install
SDL3 3.4+ yourself.

| key | does |
|---|---|
| `1`–`9` / `[` `]` | pick a scene |
| `R` | reset the scene |
| `Space` | pause |
| `.` | one step at a time 🐢 |
| `S` | slow motion, ¼ speed |
| `F1` | show what the physics sees 👀 |
| `F3` | klyph's frame stats |
| `Esc` | quit |

---

## 🎬 Scenes

### 1 · 🍮 Blob Garden

**Same shape, five tunings.** Jelly, rubber, stiff, a heavy core and a light core hop around
ramps, stairs, a low ceiling, bounce pads 🟨 and a crusher 🟫. Watch how differently they squash.

<img src="docs/img/blob-garden-details.png" alt="the Blob Garden with details on: every particle, core and floor contact" width="100%">

☝️ That's `F1`. The white dots are the particles, the red dot is the heavy core, and the yellow
feet are the particles standing on something.

More scenes land with every milestone. 🚀

---

## 🧠 How it works

Every part of the engine has a short explainer page: one picture, three bullets, the knobs, and
what breaks it. 👉 [**docs/explained**](docs/explained/README.md)

## 🧩 What's in here

| folder | what |
|---|---|
| `klyph-physics/` | 🧠 the engine. pure Kotlin, **zero dependencies** |
| `klyph-physics-bridge/` | 🔌 the klyph glue: draw what the physics sees |
| `demo/` | 🎮 the sandbox |
| `scripts/capture.sh` | 📸 records a scene into the GIFs you see here |
| `docs/` | 📚 [the design](docs/specs/2026-11-10-klyph-physics-design.md) and the explainers |

```
./gradlew allTests                                  # the engine's tests
./gradlew :demo:macosArm64Test                      # the sandbox's tests (a 10-minute garden run included)
scripts/capture.sh 1 blob-garden --from 1 --to 7    # re-record the GIF above
```

## 🚧 Status

Milestone 1 of 5. The plan is in [the design](docs/specs/2026-11-10-klyph-physics-design.md), and
the work is tracked in the [milestones](../../milestones).

It grew out of the slime in [slimy-the-blob](https://github.com/pererikbergman/slimy-the-blob). 🟢

## 📜 Licence

Apache-2.0, the same as klyph.
