# 🚦 Filters, sensors and events

⚡ **Say who collides with whom, put down zones that only notice, and hear when things touch.**

<img src="../img/sorter.gif" alt="red and blue balls, crates and blobs slide down a ramp; red ones fall through the red part of it into a bin on the left, blue ones slide over it and fall through the blue part into a bin on the right; white flashes mark each landing, and each bin counts what came in" width="100%">

## 🧠 How it works

- 👉 everything has a **category**, what it is, and a **mask**, what it collides with: two things collide only if each one's mask has the other's category 🎭
- 👉 a **sensor** never pushes or is pushed: it only notices what's in it 👀
- 👉 **events** say what began and stopped touching, what came into and left a sensor, and which joints broke 📣

<details>
<summary>🤓 the details</summary>

```kotlin
const val RED = 1 shl 1
const val RED_GATE = 1 shl 3

world.rigidBodies.setFilter(gate, RED_GATE, Filter.ALL)
world.rigidBodies.setFilter(ball, RED, Filter.ALL and RED_GATE.inv())   // passes the red gate
world.softBodies.setFilter(blob, RED, Filter.ALL and RED_GATE.inv())
world.particles.setFilter(grain, RED, Filter.ALL and Filter.SOLIDS.inv()) // falls through the level

world.rigidBodies.setSensor(zone, true)

world.step(dt)
val e = world.events
for (i in 0 until e.sensorsEntered.count) e.sensorsEntered.a(i)   // the sensor
for (i in 0 until e.contactsBegun.count) {
    e.contactsBegun.kindA(i); e.contactsBegun.a(i)     // TouchKind.RIGID_BODY, a handle
    e.contactsBegun.kindB(i); e.contactsBegun.b(i)     // or SOFT_BODY, PARTICLE, SOLIDS (-1)
}
for (i in 0 until e.jointsBroken) e.brokenJoint(i)
```

**Filters.** A category is bits 0 to 29, a mask any bits. A pair collides when
`categoryA and maskB != 0 && categoryB and maskA != 0`, so either one can refuse the other.
Everything starts in `Filter.DEFAULT` with the mask `Filter.ALL`. The level has its own bit,
`Filter.SOLIDS`: take it out of a mask and the thing falls through the floor. A soft body's filter is
all its particles'. Every place two things meet asks: rigid against rigid, against particles and soft
bodies, soft against soft, particles against the solids, and a bullet's sweep. A filtered pair never
gets a contact, so it never pushes, never wakes the other and never joins its island.

**Sensors.** A rigid body made a sensor still goes in the broadphase. Every substep, wherever it
meets something its filter lets in, it checks for an overlap and writes it down, and pushes nothing.
It notices rigid bodies, soft bodies and loose particles, but not the solids or other sensors, and
raycasts pass through it. It's checked even against bodies that are asleep, so nothing leaves a
sensor by falling asleep in it.

**Touches.** Each step, every pair that touched is written into a little hash set, however many
substeps and contact points it had: rigid bodies, soft bodies and the level, in any pair. At the end
of the step it's compared with last step's set. What's new has **begun**, what's gone has **ended**,
so each comes once. A pair touches if it pushed, or is within a quarter of a unit. Without that
slop, two boxes standing side by side would touch and stop touching every few steps.

**Sleeping touches.** A sleeping body isn't looked at, so its contacts aren't found. A pair where
everything is asleep or static is carried over from the step before, so a stack falling asleep ends
nothing. A body that's removed ends its touches in the next step.

**Joints.** A weld that pulls harder than its `breakForce` breaks, is removed, and goes in
`jointsBroken`.

</details>

## 🎛️ The knobs

| knob | default | what it does |
|---|---|---|
| `setFilter(category, mask)` | `Filter.DEFAULT`, `Filter.ALL` | on rigid bodies, soft bodies and particles: who collides with whom |
| `Filter.SOLIDS` in a mask | in | out, and it passes through the level |
| `setSensor(true)` | off | notices, never pushes |
| `breakForce` on a weld | never | how hard it can be pulled before it breaks, and says so |

## 💥 Gotchas

- 💥 **Two boxes side by side touched and stopped touching every few steps.** Resting against each
  other, they push now and then, not every substep. A pair within a quarter of a unit counts as
  touching now, push or not.
- 💥 **The test crate slid off the blob.** The events said so, begin and then end, and they were
  right: the test had the crate on a dome. It's on a flat cushion now.
- 💥 **A ball's touch began half a ball off the floor.** It hit and bounced back up within one step,
  so by the step's end it was in the air. Events say what happened in the step, not where things are
  at its end.
- 💥 **Loose particles don't get contact events.** Sand would flood them. They have their own
  readouts, `isOnFloor` and `isTouching`, and sensors do notice them.
- 💥 **A sensor in a dynamic body falls through the floor.** It doesn't touch the solids. Make it
  static, or kinematic to carry it about.
- 💥 **The tests were checked by breaking things.** Taking out the rigid filter, carrying over
  sleeping touches, or the slop each made them fail.

## 🧪 Try it

`./gradlew :demo:run`, press `→` until **Sorter** ([`Sorter.kt`](../../demo/src/nativeMain/kotlin/klyph/physics/demo/Sorter.kt)).
The red part of the ramp is in a category red things leave out of their masks, the blue part the
same for blue. Each bin's count comes from its sensor's events, and every flash is a touch that
began. Right click to drop more. 🚦

📸 `scripts/capture.sh 17 sorter --from 4 --to 12 --clean --width 640`
