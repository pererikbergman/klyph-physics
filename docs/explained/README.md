# 🧠 klyph-physics, explained

One page per part of the engine. Each one takes about a minute: a picture, three bullets, done. 🏁
Want more? Every page has the details further down.

## 📚 The pages

| | page | in one line |
|---|---|---|
| 🏃 | Verlet integration | velocity is just where you were |
| 🔁 | Substeps | many tiny steps beat a few big ones |
| 🧮 | XPBD and compliance | how soft, in real units |
| 🍮 | Soft bodies | a ring of dots, some spokes and a heavy core |
| 🧱 | Solids | tiles, slopes, and which way is out |
| 🦶 | Contacts and tags | what am I standing on? |
| 🌀 | Outline | inside or outside, and how to draw it filled |

🔜 The pages land in [#8](https://github.com/pererikbergman/klyph-physics/issues/8). New parts of the engine bring their own page with them.

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
scripts/capture.sh <scene> <name> [--from S] [--to S] [--details] [--clean]
```

It runs the scene, records it, and writes `docs/img/<name>.gif` and `docs/img/<name>.png`.
The physics steps once per frame while it records, so the same command always gives the same
picture. 🎯 Use `--clean` for the docs (no stats on screen) and `--details` to show the particles.
