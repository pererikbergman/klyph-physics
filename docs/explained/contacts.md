# 🦶 Contacts and tags

⚡ **After a step, a body can tell you which of its dots are standing on something, and what that something is.**

<img src="../img/contacts.gif" alt="a blob's feet lighting up yellow, and a bounce pad launching it" width="100%">

## 🧠 How it works

- 👉 when a solid pushes a particle **up** (any surface flatter than 60°), that particle is **on the floor**
- 👉 every solid has a **tag**: bit flags you choose, like `PAD = 1`. the body collects the tags of everything it stood on
- 👉 it keeps **this step** and **the last step** apart, because "can I jump?" asks about *before* you left the ground

<details>
<summary>🤓 the details</summary>

```kotlin
val contacts = Contacts(body.size, body.ringCount)

// every fixed step:
contacts.beginStep()                       // last step's touches become "previous"
repeat(SUBSTEPS) { body.substep(h, g) { grid.resolve(body, contacts) } }

// then ask:
contacts.touchedFloor                      // anything at all? 🦶
contacts.isOnFloor(i)                      // particle i, this step
contacts.wasOnFloor(i)                     // particle i, last step. never the core
contacts.touched(PAD)                      // stood on anything tagged PAD? 🟨
```

The Blob Garden's bounce pads are just tiles with a tag:

```kotlin
const val PAD = 1
for (c in 28..30) grid.fill(c, 20, tag = PAD)
// ...
if (blob.contacts.touched(PAD)) blob.launch(0f, -BOUNCE_SPEED, dt)   // 🚀
```

**What counts as a floor?** The push's normal must point up: `ny < -0.5` (y grows down). A flat
floor is `-1`, a 45° slope `-0.71` ✅, a wall `0` ❌, a ceiling `+1` ❌.

**No allocation**: two `BooleanArray`s, swapped by `beginStep`.

</details>

## 🎛️ The knobs

| knob | default | what it does |
|---|---|---|
| `tag` on `fill(...)` / `Solid.moving(...)` | `0` | your bit flags. or them together: `PAD or ICE` |
| floor threshold | `ny < -0.5` | steeper than 60° is a wall, not a floor. fixed for now |

## 💥 Gotchas

- 💥 **Forget `beginStep` and the touches pile up forever.** Every touch stays "this step". Call it
  once at the start of every fixed step, not every substep.
- 💥 **Only floors report tags.** Walls and ceilings push but don't record anything. A tagged wall
  stays silent.
- 💥 **Teleport? Call `clear()`.** Otherwise the body thinks it was standing on wherever it came from.

## 🧪 Try it

`./gradlew :demo:run`, scene `1` **Blob Garden**, `F1`. The yellow feet are the particles on a floor
this step. Watch a blob hit the 🟨 pads.

📸 `scripts/capture.sh 1 contacts --from 7.2 --to 8.4 --details --clean --slow --crop 0.5,0.15,0.5,0.85`
