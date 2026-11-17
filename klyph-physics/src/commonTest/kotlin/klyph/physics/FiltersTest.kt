package klyph.physics

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who collides with whom, sensors that only notice, and the events that say what touched. */
class FiltersTest {
    private val dt = TestBodies.DT
    private val tile = TestBodies.TILE

    /** A room 40 tiles wide and 30 high with a floor along its bottom two rows: its top at y = 448. */
    private fun room(config: PhysicsConfig = PhysicsConfig()) = PhysicsWorld(config).apply {
        solids = SolidGrid(40, 30, tile).apply {
            for (c in 0 until cols) for (r in 28 until 30) fill(c, r)
            for (r in 0 until 30) {
                fill(0, r)
                fill(cols - 1, r)
            }
        }
    }

    private val floor = 28 * 16f

    private fun steps(world: PhysicsWorld, seconds: Float, each: (Int) -> Unit = {}) = repeat((seconds / dt).toInt()) {
        world.step(dt)
        each(it)
    }

    private companion object {
        const val RED = 1 shl 1
        const val BLUE = 1 shl 2
        const val GATE = 1 shl 3
        const val NOT_GATE = Filter.ALL and GATE.inv()
        const val NOT_SOLIDS = Filter.ALL and Filter.SOLIDS.inv()
    }

    // --- filtering ---

    @Test
    fun filteredRigidPairsNeverCollide() {
        val world = room()
        val bodies = world.rigidBodies
        val random = Random(34)
        val categories = intArrayOf(Filter.DEFAULT, RED, BLUE)
        repeat(80) {
            val shape = if (it % 2 == 0) Shape.box(10f + random.nextFloat() * 20f, 10f + random.nextFloat() * 20f) else Shape.circle(5f + random.nextFloat() * 10f)
            val b = world.rigidBody(shape, 60f + random.nextFloat() * 520f, 40f + random.nextFloat() * 300f, angle = random.nextFloat() * 6f)
            // a mask of any of the three, but never nothing, and always the solids so nothing falls out
            var mask = 0
            for (c in categories) if (random.nextBoolean()) mask = mask or c
            if (mask == 0) mask = categories[random.nextInt(3)]
            bodies.setFilter(b, categories[random.nextInt(3)], mask or Filter.SOLIDS)
        }
        var contacts = 0
        steps(world, 4f) {
            val c = world.rigidContacts
            for (k in 0 until c.count) {
                val a = c.bodyA(k)
                val b = c.bodyB(k)
                if (b < 0) continue
                contacts++
                assertTrue(Filter.collides(bodies.category(a), bodies.mask(a), bodies.category(b), bodies.mask(b)), "bodies $a and $b collided, but their filters say they don't")
            }
        }
        assertTrue(contacts > 100, "the pile touched itself: $contacts contacts")
    }

    @Test
    fun aBodyFallsThroughAPlankItFiltersOutAndLandsOnOneItDoesNot() {
        val world = room()
        val bodies = world.rigidBodies
        val plank = world.rigidBody(Shape.box(400f, 8f), 320f, 300f, kind = BodyKind.STATIC)
        bodies.setFilter(plank, GATE, Filter.ALL)
        val through = world.rigidBody(Shape.box(20f, 20f), 250f, 200f)
        bodies.setFilter(through, RED, NOT_GATE)
        val stopped = world.rigidBody(Shape.box(20f, 20f), 390f, 200f)
        steps(world, 2f)
        assertEquals(floor - 10f, bodies.y(through), 1f, "the red box fell through the plank to the floor")
        assertEquals(300f - 4f - 10f, bodies.y(stopped), 1f, "the other one landed on it")
    }

    @Test
    fun aBlobAndSandFallThroughAPlankTheyFilterOut() {
        val world = room()
        val plank = world.rigidBody(Shape.box(500f, 8f), 320f, 300f, kind = BodyKind.STATIC)
        world.rigidBodies.setFilter(plank, GATE, Filter.ALL)
        val through = world.softBody(TestBodies.dome(), 200f, 250f)
        world.softBodies.setFilter(through, RED, NOT_GATE)
        val stopped = world.softBody(TestBodies.dome(), 440f, 250f)
        val ps = world.particles
        val grain = ps.add(320f, 200f, radius = 2f, collides = true)
        ps.setFilter(grain, RED, NOT_GATE)
        steps(world, 3f)
        assertTrue(world.softBodies.maxY(through) > floor - 3f, "the red blob reached the floor: ${world.softBodies.maxY(through)}")
        assertTrue(world.softBodies.maxY(stopped) < 300f, "the other sits on the plank: ${world.softBodies.maxY(stopped)}")
        assertTrue(ps.y(grain) > floor - 3f, "the red grain reached the floor: ${ps.y(grain)}")
        assertEquals(RED, world.softBodies.category(through))
        assertEquals(NOT_GATE, world.softBodies.mask(through))
    }

    @Test
    fun twoBlobsThatFilterEachOtherOutPassThrough() {
        val world = room()
        val below = world.softBody(TestBodies.dome(), 320f, floor)
        world.softBodies.setFilter(below, BLUE, Filter.ALL and RED.inv())
        val above = world.softBody(TestBodies.dome(), 320f, floor - 120f)
        world.softBodies.setFilter(above, RED, Filter.ALL)
        steps(world, 2f)
        assertTrue(world.softBodies.maxY(above) > floor - 3f, "the top blob sank through the other to the floor: ${world.softBodies.maxY(above)}")
    }

    @Test
    fun withoutTheSolidsBitThingsFallThroughTheLevel() {
        val world = room()
        val box = world.rigidBody(Shape.box(20f, 20f), 200f, 300f)
        world.rigidBodies.setFilter(box, Filter.DEFAULT, NOT_SOLIDS)
        val blob = world.softBody(TestBodies.dome(), 320f, 330f)
        world.softBodies.setFilter(blob, Filter.DEFAULT, NOT_SOLIDS)
        val grain = world.particles.add(450f, 300f, radius = 2f, collides = true)
        world.particles.setFilter(grain, Filter.DEFAULT, NOT_SOLIDS)
        steps(world, 1.5f)
        assertTrue(world.rigidBodies.y(box) > 30 * tile, "the box fell out of the bottom: ${world.rigidBodies.y(box)}")
        assertTrue(world.softBodies.minY(blob) > 30 * tile, "the blob fell out of the bottom: ${world.softBodies.minY(blob)}")
        assertTrue(world.particles.y(grain) > 30 * tile, "the grain fell out of the bottom: ${world.particles.y(grain)}")
    }

    @Test
    fun aBulletPassesAWallItFiltersOut() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f)))
        val bodies = world.rigidBodies
        val wall = world.rigidBody(Shape.box(1f, 400f), 300f, 0f, kind = BodyKind.STATIC)
        bodies.setFilter(wall, GATE, Filter.ALL)
        val bullet = world.rigidBody(Shape.circle(2f), 0f, 0f)
        bodies.setFilter(bullet, RED, NOT_GATE)
        bodies.setBullet(bullet, true)
        bodies.setVelocity(bullet, 5000f, 0f)
        repeat(10) { world.step(dt) }
        assertTrue(bodies.x(bullet) > 600f, "it went on through: ${bodies.x(bullet)}")
    }

    @Test
    fun aCategoryIsOneOrMoreOfBitsZeroToTwentyNine() {
        val world = room()
        val b = world.rigidBody(Shape.circle(5f), 100f, 100f)
        assertFailsWith<IllegalArgumentException> { world.rigidBodies.setFilter(b, 0, Filter.ALL) }
        assertFailsWith<IllegalArgumentException> { world.rigidBodies.setFilter(b, Filter.SOLIDS, Filter.ALL) }
        assertFailsWith<IllegalArgumentException> { world.particles.setFilter(0, 1 shl 31, Filter.ALL) }
    }

    // --- sensors ---

    /** A static sensor, a box [width] by [height] with its middle at ([x], [y]). */
    private fun sensor(world: PhysicsWorld, x: Float, y: Float, width: Float = 200f, height: Float = 40f): Int =
        world.rigidBody(Shape.box(width, height), x, y, kind = BodyKind.STATIC).also { world.rigidBodies.setSensor(it, true) }

    /** Every touch that came and went over the run, as `"in a b"` and `"out a b"` in order. */
    private fun sensorLog(world: PhysicsWorld, seconds: Float): List<String> {
        val log = ArrayList<String>()
        val e = world.events
        steps(world, seconds) {
            for (i in 0 until e.sensorsEntered.count) log += "in ${e.sensorsEntered.kindB(i)} ${e.sensorsEntered.b(i)}"
            for (i in 0 until e.sensorsExited.count) log += "out ${e.sensorsExited.kindB(i)} ${e.sensorsExited.b(i)}"
        }
        return log
    }

    @Test
    fun aSensorNeverPushesAndSaysOnceWhatCameAndWent() {
        val world = room()
        val bodies = world.rigidBodies
        val s = sensor(world, 320f, 250f)
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 150f)
        val log = sensorLog(world, 2f)
        assertEquals(listOf("in RIGID_BODY $box", "out RIGID_BODY $box"), log)
        assertEquals(floor - 10f, bodies.y(box), 1f, "it fell straight through to the floor")
        assertEquals(320f, bodies.x(box), 0.01f, "without being pushed aside")
        assertEquals(250f, bodies.y(s), 0f)
        assertTrue(bodies.isSensor(s))
    }

    @Test
    fun aSensorNoticesBlobsAndLooseParticles() {
        val world = room()
        sensor(world, 320f, 250f)
        val blob = world.softBody(TestBodies.dome(), 280f, 180f)
        val grain = world.particles.add(380f, 150f, radius = 2f, collides = true)
        val log = sensorLog(world, 2f)
        assertEquals(1, log.count { it == "in SOFT_BODY $blob" }, "$log")
        assertEquals(1, log.count { it == "out SOFT_BODY $blob" }, "$log")
        assertEquals(1, log.count { it == "in PARTICLE $grain" }, "$log")
        assertEquals(1, log.count { it == "out PARTICLE $grain" }, "$log")
    }

    @Test
    fun aSensorOnlyNoticesWhatItsFilterLetsIn() {
        val world = room()
        val s = sensor(world, 320f, 250f, width = 400f)
        world.rigidBodies.setFilter(s, Filter.DEFAULT, RED or BLUE)
        val red = world.rigidBody(Shape.box(20f, 20f), 200f, 150f)
        world.rigidBodies.setFilter(red, RED, Filter.ALL)
        world.rigidBody(Shape.box(20f, 20f), 440f, 150f)
        val log = sensorLog(world, 2f)
        assertEquals(listOf("in RIGID_BODY $red", "out RIGID_BODY $red"), log)
    }

    @Test
    fun aBodyAsleepInASensorStaysInItUntilItIsRemoved() {
        val world = room()
        val bodies = world.rigidBodies
        val s = sensor(world, 320f, floor - 20f)
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, floor - 60f)
        val log = sensorLog(world, 3f)
        assertTrue(bodies.isAsleep(box), "it fell asleep in the sensor")
        assertEquals(listOf("in RIGID_BODY $box"), log)
        bodies.remove(box)
        world.step(dt)
        assertTrue(world.events.sensorsExited.has(TouchKind.RIGID_BODY, s, TouchKind.RIGID_BODY, box), "removed, it left")
    }

    @Test
    fun raycastsPassThroughSensors() {
        val world = room()
        sensor(world, 320f, 250f)
        val hit = RayHit()
        assertTrue(world.raycast(320f, 100f, 320f, 600f, hit))
        assertEquals(HitKind.SOLID, hit.what)
    }

    // --- contact events ---

    /** Every contact that began and ended over the run, as `"+ a b"` and `"- a b"`, with the step it was in. */
    private fun contactLog(world: PhysicsWorld, seconds: Float, each: (Int) -> Unit = {}): List<Pair<Int, String>> {
        val log = ArrayList<Pair<Int, String>>()
        val e = world.events
        steps(world, seconds) { step ->
            for (i in 0 until e.contactsBegun.count) log += step to "+ ${e.contactsBegun.kindA(i)} ${e.contactsBegun.a(i)} ${e.contactsBegun.kindB(i)} ${e.contactsBegun.b(i)}"
            for (i in 0 until e.contactsEnded.count) log += step to "- ${e.contactsEnded.kindA(i)} ${e.contactsEnded.a(i)} ${e.contactsEnded.kindB(i)} ${e.contactsEnded.b(i)}"
            each(step)
        }
        return log
    }

    @Test
    fun aBoxDroppedOnTheFloorBeginsOnceAndNeverEndsAsItSettlesAndSleeps() {
        val world = room()
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 300f)
        val log = contactLog(world, 3f).map { it.second }
        assertEquals(listOf("+ RIGID_BODY $box SOLIDS -1"), log)
        assertTrue(world.rigidBodies.isAsleep(box))
    }

    @Test
    fun aBouncingBallBeginsAndEndsOncePerBounce() {
        val world = room()
        val bodies = world.rigidBodies
        val ball = world.rigidBody(Shape.circle(10f), 320f, 200f, material = Material(restitution = 0.7f))
        val lowest = HashMap<Int, Float>()
        val log = contactLog(world, 3f) { step -> lowest[step] = bodies.y(ball) }
        val kinds = log.map { it.second.take(1) }
        // they alternate: in, out, in, out, and the last may stay in
        for ((i, k) in kinds.withIndex()) assertEquals(if (i % 2 == 0) "+" else "-", k, "event $i of $log")
        assertTrue(kinds.count { it == "+" } >= 3, "it bounced a few times: $log")
        // and each one began on the floor, or bounced off it within the step: a step falls 15 at most here
        for ((step, event) in log) if (event.startsWith("+")) assertTrue(lowest[step]!! > floor - 10f - 16f, "began at step $step at ${lowest[step]}, off the floor")
    }

    @Test
    fun aStackBeginsOnceAPairAndFallingAsleepEndsNothing() {
        val world = room()
        val bodies = world.rigidBodies
        val boxes = ArrayList<Int>()
        for (row in 0 until 4) for (k in 0 until 4 - row) boxes += world.rigidBody(Shape.box(24f, 24f), 320f - (3 - row) * 12f + k * 24f, floor - 12f - row * 24f)
        val log = contactLog(world, 3f).map { it.second }
        assertTrue(boxes.all { bodies.isAsleep(it) }, "the stack fell asleep")
        assertTrue(log.none { it.startsWith("-") }, "nothing ended: $log")
        assertEquals(log.toSet().size, log.size, "no pair began twice: $log")
        // 4 on the floor, 3 + 2 + 1 rows each on two below, and side by side the ones that lean
        assertTrue(log.size >= 4 + 2 * 6, "the pairs that hold the stack up all began: $log")
        val top = boxes.last()
        bodies.remove(top)
        world.step(dt)
        assertTrue(world.events.contactsEnded.involves(TouchKind.RIGID_BODY, top), "removed, the top box's touches ended")
        assertEquals(0, world.events.contactsBegun.count)
    }

    @Test
    fun softBodiesBeginTouchingRigidBodiesEachOtherAndTheFloor() {
        val world = room()
        val bodies = world.rigidBodies
        // a wide flat cushion, so the crate stays on it
        val cushion = world.softBody(SoftBodies.box(160f, 24f, across = 16, down = 3, coreMass = 20f), 200f, floor)
        val crate = world.rigidBody(Shape.box(20f, 20f), 200f, floor - 60f)
        val other = world.softBody(TestBodies.dome(), 440f, floor)
        val falling = world.softBody(TestBodies.dome(), 440f, floor - 80f)
        val log = contactLog(world, 2f).map { it.second }
        assertTrue("+ RIGID_BODY $crate SOFT_BODY $cushion" in log, "$log")
        assertTrue("+ SOFT_BODY ${minOf(other, falling)} SOFT_BODY ${maxOf(other, falling)}" in log, "$log")
        assertTrue("+ SOFT_BODY $cushion SOLIDS -1" in log, "$log")
        // the crate is still on the cushion, which sags round it, so their touch hasn't ended
        assertTrue(bodies.x(crate) in world.softBodies.minX(cushion)..world.softBodies.maxX(cushion), "the crate is over the cushion")
        assertEquals("+", log.last { it.drop(2) == "RIGID_BODY $crate SOFT_BODY $cushion" }.take(1), "$log")
        // each pair begins before it ends, and they take turns
        for (pair in log.map { it.drop(2) }.toSet()) {
            val turns = log.filter { it.drop(2) == pair }.map { it.take(1) }
            for ((i, t) in turns.withIndex()) assertEquals(if (i % 2 == 0) "+" else "-", t, "$pair: $turns")
        }
        // lifted away, the crate stops touching the cushion
        bodies.moveTo(crate, 200f, 100f)
        world.step(dt)
        assertTrue(world.events.contactsEnded.has(TouchKind.RIGID_BODY, crate, TouchKind.SOFT_BODY, cushion))
    }

    @Test
    fun aWeldThatBreaksIsSaidToOnce() {
        val world = room()
        val box = world.rigidBody(Shape.box(20f, 20f), 320f, 200f)
        val weld = world.joints.weld(box, Joints.WORLD, 320f, 190f, breakForce = 100f)
        var breaks = 0
        steps(world, 1f) {
            for (i in 0 until world.events.jointsBroken) {
                assertEquals(weld, world.events.brokenJoint(i))
                breaks++
            }
        }
        assertEquals(1, breaks)
        assertFalse(world.joints.isAlive(weld))
    }

    @Test
    fun eventsFireOncePerBeginAndEndInARattlingPile() {
        val world = room()
        val bodies = world.rigidBodies
        val random = Random(35)
        repeat(40) {
            val shape = if (it % 2 == 0) Shape.box(14f + random.nextFloat() * 14f, 14f + random.nextFloat() * 14f) else Shape.circle(7f + random.nextFloat() * 7f)
            world.rigidBody(shape, 120f + random.nextFloat() * 400f, 100f + random.nextFloat() * 280f, material = Material(restitution = random.nextFloat() * 0.6f))
        }
        val blob = world.softBody(TestBodies.dome(), 320f, 120f)
        // what touches, as the events have said so far
        val touching = HashSet<String>()
        var begun = 0
        val e = world.events
        steps(world, 5f) { step ->
            // shaken now and then, so things come apart and together again
            if (step % 60 == 30) for (b in 0 until bodies.end) if (bodies.isAlive(b)) bodies.setVelocity(b, (random.nextFloat() - 0.5f) * 300f, -300f * random.nextFloat())
            val seen = HashSet<String>()
            for (i in 0 until e.contactsBegun.count) {
                val key = "${e.contactsBegun.kindA(i)} ${e.contactsBegun.a(i)} ${e.contactsBegun.kindB(i)} ${e.contactsBegun.b(i)}"
                assertTrue(seen.add(key), "step $step: $key began twice in one step")
                assertTrue(touching.add(key), "step $step: $key began while it touched")
                begun++
            }
            for (i in 0 until e.contactsEnded.count) {
                val key = "${e.contactsEnded.kindA(i)} ${e.contactsEnded.a(i)} ${e.contactsEnded.kindB(i)} ${e.contactsEnded.b(i)}"
                assertTrue(touching.remove(key), "step $step: $key ended, but hadn't begun")
            }
            // every pair that pushed in the last substep is touching, as the events say
            val c = world.rigidContacts
            for (k in 0 until c.count) {
                if (c.push(k) <= 0f) continue
                val a = c.bodyA(k)
                val b = c.bodyB(k)
                val key = if (b < 0) "RIGID_BODY $a SOLIDS -1" else "RIGID_BODY ${minOf(a, b)} RIGID_BODY ${maxOf(a, b)}"
                assertTrue(key in touching, "step $step: $key pushed, but the events don't say it touches")
            }
        }
        assertTrue(begun > 100, "plenty began: $begun")
        assertTrue(touching.any { it.contains("SOFT_BODY $blob") }, "and the blob is touching something: $touching")
    }
}
