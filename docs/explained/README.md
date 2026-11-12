# 🧠 klyph-physics, explained

One page per part of the engine. Each one takes about a minute: a picture, three bullets, done. 🏁
Want more? Every page has the details further down.

## 📚 The pages

| | page | in one line |
|---|---|---|
| 🌍 | [The world and one step](world.md) | one world, one `step`, no garbage |
| 🏃 | [Verlet integration](verlet.md) | velocity is just where you were |
| 🔁 | [Substeps](substeps.md) | many tiny steps beat a few big ones |
| 🧮 | [XPBD and compliance](xpbd.md) | how soft, in real units |
| 🔗 | [Constraints](constraints.md) | distance, rope, pin, area and bend, each with its own softness |
| 🍮 | [Soft bodies](soft-bodies.md) | a ring of dots, some spokes and a heavy core |
| 🧱 | [Solids](solids.md) | tiles, slopes, and which way is out |
| 🦶 | [Contacts and tags](contacts.md) | what am I standing on? |
| 🌀 | [Outline](outline.md) | inside or outside, and how to draw it filled |
| 🗂️ | [Broadphase](broadphase.md) | who might touch whom, without checking everyone |
| 💥 | [Bodies hitting bodies](bodies.md) | blobs, balls and sand that don't go through each other |
| 🧽 | [Friction and bounce](friction.md) | what grips, what slides, what bounces back |
| 🪢 | [Ropes, chains and cloth](ropes.md) | one line for a rope, a chain or a sheet, and tearing |
| 🖐️ | [Grab](grab.md) | drag anything with the mouse, and never break it |
| 🔍 | [Debug views](debug-views.md) | see the solver work: stress, contacts, the grid |
| 📦 | [Rigid bodies and rotation](rigid-bodies.md) | one thing that moves and turns |
| 🔷 | [Shapes, mass and inertia](shapes.md) | what a body is made of, and how hard it is to turn |
| ✂️ | [SAT and contact points](sat.md) | where two shapes touch, to the point |

Read them in that order and each one builds on the last. 🧱 New parts of the engine bring their own page with them.

---

## 📝 The template

Every page looks like this. Copy it, fill it in, keep it short. ✂️

````markdown
# 🍮 Name of the thing

⚡ **One line: what it is, the way you'd say it to a friend.**

<img src="../img/<name>.gif" alt="what the picture shows" width="100%">

## 🧠 How it works

- 👉 the first idea, one line
- 👉 the second idea, one line
- 👉 the third idea, one line

<details>
<summary>🤓 the details</summary>

The longer version, for those who want it: the maths, the code, the why.

</details>

## 🎛️ The knobs

| knob | default | turn it up 🔼 | turn it down 🔽 |
|---|---|---|---|
| `name` | `1.0` | what happens | what happens |

## 💥 Gotchas

- 💥 **What breaks it**: why, and what to do about it.

## 🧪 Try it

`./gradlew :demo:run`, press `1` for **Scene name**, then `F1` to see the particles. 👀
````

## ✍️ The rules

- 🖼️ **Picture first.** Every page has one, recorded from the demo with
  [`scripts/capture.sh`](../../scripts/capture.sh), so it's real physics, never a drawing.
- 3️⃣ **Three bullets**, max. If it doesn't fit, it goes in 🤓 the details.
- ✂️ **Short sentences.** One idea each.
- 🎛️ **Every knob** that the code has goes in the table, with what turning it does.
- 💥 **Every gotcha** that bit us goes in, even the embarrassing ones.
- 🚢 **A page ships with its feature**, in the same PR. Never after.

## 📸 Making the picture

```
scripts/capture.sh <scene> <name> [--from S] [--to S] [--details] [--view N] [--grid] [--clean]
                   [--slow] [--crop X,Y,W,H] [--width W] [--drag X,Y]
```

It runs the scene, records it, and writes `docs/img/<name>.gif` and `docs/img/<name>.png`.
The physics steps once per frame while it records, so the same command always gives the same
picture. 🎯 Use `--clean` for the docs (no stats on screen), `--view 1` to `3` for the
[🔍 debug views](debug-views.md) (`--details` is `--view 3`), `--grid` for the broadphase, `--slow` for
half speed, `--crop` for a close-up and `--drag` for a scripted mouse. Every page ends with the 📸 command that made its GIF.
