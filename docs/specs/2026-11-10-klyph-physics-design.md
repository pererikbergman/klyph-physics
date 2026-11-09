# klyph-physics: design

Date: 2026-11-10. Status: approved.

## Goal

A small but complete 2D physics engine for [klyph](https://github.com/inemtsev/klyph): soft bodies,
ropes, cloth, rigid bodies and joints in **one XPBD solver**, shipped as a module that sits next to
the engine instead of inside it. A sandbox demo shows every feature, and at the end a playable
showcase level uses all of them together.

It starts from the `klyph.physics` module grown in
[slimy-the-blob](https://github.com/pererikbergman/slimy-the-blob): a soft body made of a ring of
particles around a heavy core, held by XPBD distance and area constraints, colliding with a grid of
tiles, slopes and moving solids. Slimy keeps its own copy, and nothing here depends on it.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Solver | One XPBD solver for particles and rigid bodies (Müller et al. 2020, Macklin et al. 2019 "Small Steps") | Contacts between soft and rigid bodies are just constraints, it is stable with substeps, there is one code path to make fast, and the slime already proves the approach |
| Engine dependencies | None: pure Kotlin in `commonMain` | Testable without a window, and it can move into klyph or anywhere else |
| klyph glue | A separate `klyph-physics-bridge` module | The engine never imports klyph |
| Packages | One flat package per module | Easy to find things, and easy to move |
| Demo | A sandbox of scenes first, a playable showcase level last | The sandbox lets you poke each feature; the level proves they work together |
| Licence | Apache-2.0, as klyph | Makes a later move into the engine simple |
| Docs | Short, punchy, picture-first explainer per engine part | See [Docs](#docs) |

## Layout

```
klyph-physics/                    repo root
  klyph-physics/                  the engine, package klyph.physics, commonMain, no dependencies
  klyph-physics-bridge/           package klyph.physics.bridge: App.addPhysics, DebugDraw
  demo/                           the sandbox app (klyph plugin)
  showcase/                       the playable level (later phase)
  docs/                           explainers, specs, plans, images
```

The root declares the Kotlin 2.4.20 and klyph 1.0.0 plugins with `apply false`. The engine and the
bridge use `kotlin("multiplatform")` with the targets klyph builds for (macosArm64, linuxX64,
linuxArm64, mingwX64); `demo` and `showcase` apply the klyph plugin.

## Engine architecture

One world, data stored as parallel arrays, one step function.

```kotlin
val world = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 900f), substeps = 8))
val blob  = world.softBody(SoftBodies.semiDisc(...), at = ...)
val crate = world.rigidBody(Shape.box(32f, 32f), at = ..., density = 1f)
world.hinge(crate, blob.particle(0), ...)
world.step(dt)
```

| File | Job |
|---|---|
| `PhysicsWorld.kt` | Owns everything; `step(dt)` runs the substep loop |
| `Particles.kt` | Particle store: x, y, prevX, prevY, invMass, radius, flags |
| `RigidBodies.kt` | Rigid body store: position, angle, previous values, invMass, invInertia, shape |
| `Shapes.kt` | Circle, convex polygon, capsule; bounding boxes, mass and inertia |
| `Constraints.kt` | XPBD particle constraints: distance (and one-sided rope), area, bending, pin |
| `SoftBodies.kt` | Builders: ring-and-core blobs, ropes, chains, cloth |
| `Joints.kt` | Hinge, slider, weld and distance joints, with limits and motors |
| `Broadphase.kt` | Spatial hash grid, rebuilt once per step, boxes grown by the step's motion |
| `Narrowphase.kt` | Contacts: circle/polygon with SAT and clipping, particle vs. shape, particle vs. outline |
| `Contacts.kt` | Contact constraints: non-penetration, static and dynamic friction, restitution |
| `Solids.kt` | Static level geometry: tile grid, slopes, moving solids |
| `Queries.kt` | Raycast, point and overlap queries |
| `Events.kt` | Contact begin and end, floors, tags, sensors |

### One step

```
step(dt):
  broadphase → candidate pairs                 once per step
  repeat substeps (h = dt / substeps):
    integrate: gravity, prev ← current, current += velocity · h
    narrowphase on candidate pairs → contacts
    solve constraints, joints and contacts     one XPBD pass, each with a compliance
    velocities = (current − prev) / h
    velocity pass: restitution, dynamic friction
  events
```

### Rules

- `step` allocates nothing. Stores grow by doubling.
- Bodies, particles and joints are plain `Int` handles. No per-body objects in hot loops.
- The same inputs give the same results on one platform. Tests rely on it.
- Compliance, not stiffness: how soft a constraint is does not depend on the substep count.
- y grows down, as in klyph.

## Phases

Each phase is a GitHub milestone. Its issues list the work, its demo scenes are the acceptance
test, and its explainer pages are part of done.

1. **Init and sandbox shell:** repo skeleton, the imported module, CI, the demo app with scene
   switching and debug drawing, a scene of today's soft bodies, and the docs skeleton.
2. **Particle world:** `PhysicsWorld`, shared particle and constraint stores, soft bodies ported
   onto them, ropes, chains and cloth, broadphase, collision between bodies, friction, mouse
   dragging.
3. **Rigid bodies:** rotation, shapes and mass, SAT narrowphase, rigid contacts with friction and
   restitution, stable stacking, two-way coupling with soft bodies, joints with limits and motors.
4. **Hardening:** sleeping, continuous collision for fast bodies, queries, collision filtering and
   sensors, performance targets, `App.addPhysics` for klyph, API review and publishing.
5. **Showcase:** a playable level with one room per feature, a playthrough bot, and v1.0.0.

## Docs

The docs read like the [slimy-the-blob README](https://github.com/pererikbergman/slimy-the-blob):
short, punchy, picture-first, skimmable in seconds, with emoji headers. They should be TikTok-friendly
with a touch of ADHD energy.

- The README opens with a **10-second version**, then a GIF per scene.
- **Every part of the engine gets its own explainer page** in `docs/explained/`, using one template:
  1. ⚡ **One line:** what it is.
  2. 🖼️ **A picture or GIF** from the demo.
  3. 🧠 **How it works** in at most three bullets, then the details for those who want them.
  4. 🎛️ **The knobs:** every tuning value and what turning it does.
  5. 💥 **Gotchas:** what breaks it.
  6. 🧪 **Try it:** which demo scene and keys show it.
- An explainer page ships with the feature, never after it.

## Testing

- Each engine part has unit tests in `commonTest`.
- Each phase has scene-level tests that run the demo's scenes without a window and check the
  outcome: a tower still stands, a rope stays in one piece, nothing tunnels, and nothing turns NaN.
- CI runs the engine's tests on macOS and Linux.
- Benchmarks have targets and run on demand rather than in CI.

## Out of scope

3D; fluids (SPH); concave polygons other than as compounds of convex ones; networking and rollback.
