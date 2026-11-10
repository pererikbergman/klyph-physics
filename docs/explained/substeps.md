# 🔁 Substeps

⚡ **Don't take one big step per frame. Take four small ones. Everything gets stiffer, steadier and harder to tunnel through.**

<img src="../img/substeps.gif" alt="a blob hitting a bounce pad at full speed without going through it" width="100%">

## 🧠 How it works

- 👉 the game ticks 60 times a second. each tick is cut into **4 substeps** of 1/240 s
- 👉 each substep: move every particle once, then **6 passes** of "fix the constraints, push out of walls"
- 👉 small steps mean small moves, so particles **can't skip through** a wall, and soft things stay as soft as you tuned them

<details>
<summary>🤓 the details</summary>

One fixed step of a blob, from the demo's `Blob.step`:

```kotlin
contacts.beginStep()
val h = dt / SUBSTEPS                       // 1/60 s / 4 = 1/240 s
repeat(SUBSTEPS) {
    body.substep(h, gravity) { grid.resolve(body, contacts) }
}
```

and inside `SoftBody.substep`:

```kotlin
integrate(gravity, h)                       // 🏃 every particle moves once
repeat(tuning.relaxIterations) {            // 6 times:
    relax(h)                                //   🧮 pull every constraint toward rest
    collide()                               //   🧱 push out of the solids: walls get the last word
}
```

**Why substeps beat more iterations:** an iteration only fixes constraints *again*, with the same
big move behind it. A substep makes the move itself smaller. The error in XPBD shrinks with `h²`,
so halving the step cuts it by four. ✂️

**Tunnelling, in numbers.** A blob off a bounce pad goes 680 units/s. In one 1/60 s step that's
**11 units**, most of a 16-unit tile. In a 1/240 s substep it's **2.8 units**. No skipping. 🧱

That's `4 × 6 = 24` passes over every constraint per tick. Each one is cheap. 🏎️

</details>

## 🎛️ The knobs

| knob | default | turn it up 🔼 | turn it down 🔽 |
|---|---|---|---|
| `Blob.SUBSTEPS` (demo) | `4` | stiffer, steadier, no tunnelling, costs more | cheaper, but bodies go soft and fast ones tunnel 👻 |
| `SoftBodyTuning.relaxIterations` | `6` | stiff constraints get closer to rigid | cheaper, things sag a bit |
| klyph's `fixedRate` | `60` | same as more substeps | same as fewer |

🔜 Milestone 2 moves the substep count into `PhysicsConfig`, so a whole world shares one.

## 💥 Gotchas

- 💥 **More substeps = more damping.** Damping is applied every substep. Change `SUBSTEPS` and
  re-check `damping` (see [🏃 Verlet](verlet.md)).
- 💥 **Iterations aren't free stiffness.** Past a handful, more passes barely help. If a body
  is too soft, add a substep or lower its compliance.
- 💥 **`maxStep` must stay under half the thinnest wall.** Substeps make moves small, and
  `maxStep` makes sure of it, so a particle never jumps past a wall's middle.

## 🧪 Try it

`./gradlew :demo:run`, scene `1` **Blob Garden**. Watch the blob that lands on the yellow pads
🟨: it goes up at 680 units/s and never ends up inside the ceiling. `S` slows it to ¼ speed.

📸 `scripts/capture.sh 1 substeps --from 7 --to 9 --details --clean --crop 0.45,0,0.55,1`
