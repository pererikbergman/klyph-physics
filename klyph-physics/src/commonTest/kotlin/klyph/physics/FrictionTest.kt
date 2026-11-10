package klyph.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrictionTest {
    private val dt = TestBodies.DT
    private val tile = TestBodies.TILE
    private val g = TestBodies.GRAVITY

    /**
     * A world whose floor is tilted [degrees] under its gravity: gravity leans that far instead, which
     * is the same thing, on a floor as flat as the tiles make it.
     */
    private fun tilted(degrees: Float, material: Material): PhysicsWorld {
        val angle = degrees * PI.toFloat() / 180f
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec(g * sin(angle), g * cos(angle))))
        world.solids = SolidGrid(200, 12, tile).apply { for (c in 0 until cols) fill(c, 11, material = material) }
        return world
    }

    @Test
    fun aParticleOnAThirtyDegreeSlopeStaysPutWhenItsGripIsOverTheTangentAndSlidesWhenUnder() {
        // tan 30° is 0.577. It starts at rest on the surface: static friction only holds what isn't
        // already sliding, and with the same grip sliding as standing it can't get going.
        fun slid(grip: Float): Float {
            val material = Material(friction = grip, staticFriction = grip)
            val world = tilted(30f, material)
            val p = world.particles.add(100f, 11 * tile, material = material)
            repeat(120) { world.step(dt) }
            return world.particles.x(p) - 100f
        }
        assertEquals(0f, slid(0.62f), 0.05f, "0.62 holds it")
        assertTrue(slid(0.52f) > 20f, "0.52 doesn't: it slid ${slid(0.52f)}")
    }

    /**
     * Firm enough not to roll. The default dome, with its soft spokes and heavy core, sags downhill
     * until it's a wheel, and with grip it rolls down a 30° slope instead of sliding: that's friction
     * working, but not what these tests are about.
     */
    private val firm = SoftBodyTuning(spokeCompliance = 0.0001f, areaCompliance = 0.0001f)

    @Test
    fun aBlobOnAThirtyDegreeSlopeStaysPutWithHighFrictionAndSlidesWithLow() {
        fun slid(material: Material): Float {
            val world = tilted(30f, material)
            val blob = world.softBody(TestBodies.dome(), 100f, 11 * tile, firm, material)
            repeat(30) { world.step(dt) }
            val start = world.softBodies.centerX(blob)
            repeat(180) { world.step(dt) }
            return world.softBodies.centerX(blob) - start
        }
        val high = slid(Material.RUBBER)
        val low = slid(Material(friction = 0.1f, staticFriction = 0.15f))
        assertTrue(abs(high) < 2f, "rubber stays put: slid $high")
        assertTrue(low > 100f, "slippery slides: $low")
    }

    @Test
    fun aParticleOnTheFortyFiveDegreeRampTilesStaysWithGripOverOneAndSlidesUnder() {
        // tan 45° is 1, and the push comes along the slope's own diagonal normal.
        fun slid(grip: Float): Float {
            val material = Material(friction = grip, staticFriction = grip)
            val world = PhysicsWorld()
            world.solids = SolidGrid(30, 14, tile).apply {
                // a ramp rising to the right from (4, 12) to (14, 2), on full tiles
                for (k in 0 until 10) {
                    fill(4 + k, 12 - k, SolidShape.RISING, material = material)
                    for (r in 13 - k..13) fill(4 + k, r, material = material)
                }
                for (c in 0 until cols) fill(c, 13, material = material)
            }
            // on the surface of tile (10, 6), halfway along it
            val p = world.particles.add(168f, 104f, material = material)
            repeat(120) { world.step(dt) }
            return 168f - world.particles.x(p)
        }
        assertEquals(0f, slid(1.05f), 0.05f, "1.05 holds it")
        assertTrue(slid(0.9f) > 20f, "0.9 doesn't: it slid ${slid(0.9f)}")
    }

    @Test
    fun aBoxSetDownOnTheFortyFiveDegreeRampTilesHoldsWithGripAndSlidesWithout() {
        // The ramp falls to the right along y = x, on tiles (2, 2) to (11, 11) with ground under them.
        // Stiff boxes: a soft body with a heavy core sags downhill until it's round and rolls, and at
        // 45° no grip stops a wheel (see the explainer). With a grip of 2 against tan 45° = 1 it holds.
        fun slid(material: Material): Float {
            val world = PhysicsWorld()
            world.solids = SolidGrid(20, 14, tile).apply {
                for (k in 0 until 10) {
                    fill(2 + k, 2 + k, SolidShape.FALLING, material = material)
                    for (r in 3 + k until rows) fill(2 + k, r, material = material)
                }
                for (c in 12 until cols) fill(c, 13, material = material)
            }
            val stiff = SoftBodyTuning(edgeCompliance = 0f, spokeCompliance = 0f, areaCompliance = 0f)
            val box = world.softBody(SoftBodies.box(28f, 16f, across = 7, down = 4, coreMass = 20f), 96f, 80f, stiff, material)
            setDownOnTheRamp(world, box)
            val start = world.softBodies.centerX(box)
            repeat(120) { world.step(dt) }
            return world.softBodies.centerX(box) - start
        }
        val grip = slid(Material(friction = 1.5f, staticFriction = 2f))
        val plain = slid(Material.DEFAULT)
        val ice = slid(Material.ICE)
        assertEquals(0f, grip, 1f, "grip 2 holds it")
        assertTrue(plain > 20f, "the default slides: $plain")
        assertTrue(ice > plain * 2f, "ice slides much further: $ice against $plain")
    }

    /** Turns [box] to lie along the ramp y = x and sets it down half a unit off it, at rest. */
    private fun setDownOnTheRamp(world: PhysicsWorld, box: Int) {
        val bodies = world.softBodies
        val ps = world.particles
        bodies.rotate(box, PI.toFloat() / 4f)
        val s = sin(PI.toFloat() / 4f)
        // how far each particle is above the ramp, along its normal (s, -s)
        var lowest = Float.MAX_VALUE
        for (i in 0 until bodies.ringCount(box)) {
            val p = bodies.particle(box, i)
            lowest = minOf(lowest, (ps.x(p) - ps.y(p)) * s)
        }
        val by = 0.5f - lowest
        for (i in 0..bodies.ringCount(box)) {
            val p = bodies.particle(box, i)
            ps.moveTo(p, ps.x(p) + by * s, ps.y(p) - by * s)
        }
    }

    @Test
    fun aSlidingParticleStopsWhereCoulombSaysItShould() {
        // v² / (2 μ g): 300 units/s with μ = 0.5 under 1500 stops in 60 units
        val world = PhysicsWorld(PhysicsConfig(damping = 0f))
        world.solids = SolidGrid(60, 12, tile).apply { for (c in 0 until cols) fill(c, 11, material = Material(friction = 0.5f, staticFriction = 0.5f)) }
        val p = world.particles.add(100f, 11 * tile, material = Material(friction = 0.5f, staticFriction = 0.5f))
        world.step(dt)
        world.setVelocity(p, 300f, 0f)
        repeat(120) { world.step(dt) }
        assertEquals(160f, world.particles.x(p), 6f)
        assertEquals(0f, world.velocityX(p), 0.01f)
    }

    @Test
    fun aBallBouncesToItsRestitutionSquaredTimesTheHeightItFellFrom() {
        fun apex(restitution: Float): Float {
            val world = PhysicsWorld(PhysicsConfig(damping = 0f))
            val floor = 30 * tile
            world.solids = SolidGrid(10, 31, tile).apply { for (c in 0 until cols) fill(c, 30) }
            val ball = world.particles.add(80f, floor - 4f - 200f, radius = 4f, material = Material(restitution = restitution))
            // down to the floor, then up to the top of the first bounce
            var landed = false
            var top = floor
            repeat(240) {
                world.step(dt)
                val y = world.particles.y(ball) + 4f
                if (y >= floor - 0.01f) landed = true
                if (landed && world.velocityY(ball) > 0f && top < floor) return floor - top
                if (landed) top = minOf(top, y)
            }
            return floor - top
        }
        assertEquals(200f * 0.25f, apex(0.5f), 200f * 0.25f * 0.1f, "e = 0.5 bounces a quarter as high")
        assertEquals(200f * 0.64f, apex(0.8f), 200f * 0.64f * 0.1f, "e = 0.8, 64%")
        assertTrue(apex(0f) < 1f, "e = 0 stops dead: ${apex(0f)}")
    }

    @Test
    fun aBouncyThingAtRestStaysAtRest() {
        val world = PhysicsWorld()
        world.solids = SolidGrid(10, 12, tile).apply { for (c in 0 until cols) fill(c, 11) }
        val ball = world.particles.add(80f, 11 * tile - 4f, radius = 4f, material = Material(restitution = 0.9f))
        repeat(120) { world.step(dt) }
        assertEquals(11 * tile - 4f, world.particles.y(ball), 0.01f)
        assertEquals(0f, world.velocityY(ball), 0.5f)
    }

    @Test
    fun twoBodiesDroppedOnEachOtherStayStackedWithGrip() {
        // Without friction the one on top slides off (see CollisionTest); with grip it stays.
        val world = PhysicsWorld()
        world.solids = SolidGrid(20, 12, tile).apply { for (c in 0 until cols) fill(c, 11) }
        val floor = 11 * tile
        // Firm: soft boxes wobble, and a wobbling box rocks itself off the other in a second or two.
        val box = SoftBodies.box(32f, 16f, across = 8, down = 4, coreMass = 20f)
        val stiff = SoftBodyTuning(spokeCompliance = 0.00002f, areaCompliance = 0.00002f)
        val below = world.softBody(box, 160f, floor, stiff, Material.RUBBER)
        val above = world.softBody(box, 160f, floor - 60f, stiff, Material.RUBBER)
        repeat(300) { world.step(dt) }
        val bodies = world.softBodies
        assertTrue(bodies.maxY(above) < floor - 10f, "still on top: its base at ${bodies.maxY(above)}")
        assertTrue(abs(bodies.centerX(above) - bodies.centerX(below)) < 8f, "and on top of it, not beside it")
    }

    @Test
    fun aMaterialMustMakeSense() {
        assertFailsWith<IllegalArgumentException> { Material(friction = -1f) }
        assertFailsWith<IllegalArgumentException> { Material(restitution = 1.5f) }
        assertFailsWith<IllegalArgumentException> { PhysicsConfig(bounceSpeed = -1f) }
    }
}
