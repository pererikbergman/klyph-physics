package klyph.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ropes, chains and cloth: the builders, and links that tear. */
class StrandsTest {
    private val dt = TestBodies.DT

    @Test
    fun aRopeIsItsParticlesEvenlyFromEndToEndWithItsStartPinned() {
        val world = PhysicsWorld()
        val rope = world.rope(0f, 0f, 100f, 0f, segments = 10)
        assertEquals(11, rope.particles.size)
        assertEquals(10, rope.links.size)
        assertEquals(0, rope.bends.size, "no bends unless asked")
        for (i in 0..10) assertEquals(i * 10f, world.particles.x(rope.particles[i]), 0.001f)
        assertEquals(0f, world.particles.inverseMass(rope.first), "the start hangs from where it is")
        assertEquals(1f, world.particles.inverseMass(rope.last))
        assertTrue(world.constraints.isRope(rope.links[0]), "a rope's links go slack")
        assertEquals(100f, rope.restLength(world), 0.001f)
    }

    @Test
    fun aHangingRopeStaysWithinOnePercentOfItsLength() {
        val world = PhysicsWorld()
        val rope = world.rope(100f, 0f, 100f, 160f, segments = 20)
        repeat(300) { world.step(dt) }
        val stretch = rope.length(world) / rope.restLength(world) - 1f
        assertTrue(abs(stretch) < 0.01f, "stretched by ${stretch * 100}%")
        assertEquals(100f, world.particles.x(rope.last), 0.5f, "and hangs straight down")
    }

    @Test
    fun aRopeSwungSidewaysStillKeepsItsLengthOnceItHangs() {
        val world = PhysicsWorld()
        val rope = world.rope(100f, 0f, 260f, 0f, segments = 20)
        repeat(900) { world.step(dt) }
        val stretch = rope.length(world) / rope.restLength(world) - 1f
        assertTrue(abs(stretch) < 0.01f, "stretched by ${stretch * 100}%")
    }

    @Test
    fun aChainHasRigidLinksAndBendsAndHangsWithBothEndsPinned() {
        val world = PhysicsWorld()
        val chain = world.chain(0f, 0f, 120f, 0f, segments = 12, pinEnd = true)
        assertFalse(world.constraints.isRope(chain.links[0]), "a chain's links push too")
        assertEquals(11, chain.bends.size)
        repeat(300) { world.step(dt) }
        val middle = chain.particles[6]
        assertTrue(world.particles.y(middle) > 5f, "it sags in the middle: ${world.particles.y(middle)}")
        assertTrue(abs(chain.length(world) / chain.restLength(world) - 1f) < 0.02f, "and barely stretches")
    }

    @Test
    fun aClothIsAGridWithStructuralShearAndBendConstraintsAndItsTopRowPinned() {
        val world = PhysicsWorld()
        val cloth = world.cloth(0f, 0f, cols = 5, rows = 4, spacing = 10f)
        assertEquals(20, cloth.particles.size)
        assertEquals(4 * 4 + 5 * 3 + 2 * 4 * 3, cloth.links.size, "rows, columns and both diagonals of every square")
        assertEquals(3 * 4 + 2 * 5, cloth.bends.size, "along every row and every column")
        assertEquals(30f, world.particles.x(cloth.particle(3, 2)), 0.001f)
        assertEquals(20f, world.particles.y(cloth.particle(3, 2)), 0.001f)
        for (col in 0 until 5) assertEquals(0f, world.particles.inverseMass(cloth.particle(col, 0)))
        assertEquals(1f, world.particles.inverseMass(cloth.particle(0, 1)))
    }

    @Test
    fun aHangingClothHangsWithoutKnots() {
        val world = PhysicsWorld()
        val cloth = world.cloth(100f, 20f, cols = 16, rows = 12, spacing = 8f)
        repeat(600) { world.step(dt) }
        val ps = world.particles
        // every square still faces the same way: none folded over another
        for (row in 0 until cloth.rows - 1) for (col in 0 until cloth.cols - 1) {
            val a = cloth.particle(col, row)
            val b = cloth.particle(col + 1, row)
            val c = cloth.particle(col, row + 1)
            val cross = (ps.x(b) - ps.x(a)) * (ps.y(c) - ps.y(a)) - (ps.y(b) - ps.y(a)) * (ps.x(c) - ps.x(a))
            assertTrue(cross > 0f, "the square at ($col, $row) folded over: $cross")
        }
        for (link in cloth.links) assertTrue(world.constraints.strain(link) < 0.05f, "link $link is stretched ${world.constraints.strain(link)}")
        assertEquals(20f + 11 * 8f, ps.y(cloth.particle(8, 11)), 3f, "and it hangs at its full height")
    }

    @Test
    fun aLinkPulledPastItsTearLimitBreaksAndTakesTheBendsAcrossIt() {
        val world = PhysicsWorld(PhysicsConfig(gravity = Vec.ZERO))
        val rope = world.rope(0f, 0f, 40f, 0f, segments = 4, bendCompliance = 0f)
        val cs = world.constraints
        // only the last link can tear; the others stretch as far as they're pulled
        cs.setTear(rope.links[3], 1.5f)
        world.step(dt)
        assertEquals(0, cs.tornCount, "nothing tears at rest")
        // hold the end far away: its link breaks, and so do the bends that crossed it
        cs.pin(rope.last, 200f, 0f)
        repeat(3) { world.step(dt) }
        assertTrue(cs.tornCount >= 1)
        assertFalse(cs.isLinkAlive(rope.links[3]))
        assertFalse(cs.isBendAlive(rope.bends[2]), "the bend at the torn link's start went with it")
        assertTrue(cs.isLinkAlive(rope.links[0]), "the rest holds")
        assertEquals(1, cs.tornCount)
    }

    @Test
    fun aClothPulledApartTearsCleanlyIntoTwo() {
        val world = PhysicsWorld()
        val cols = 20
        val cloth = world.cloth(100f, 20f, cols = cols, rows = 10, spacing = 8f, tearAt = 1.6f)
        val ps = world.particles
        repeat(60) { world.step(dt) }
        // drag the right half of the top row away to the right
        repeat(240) {
            for (col in cols / 2 until cols) {
                val p = cloth.particle(col, 0)
                ps.moveTo(p, ps.x(p) + 1.5f, ps.y(p))
            }
            world.step(dt)
        }
        val cs = world.constraints
        assertTrue(cs.tornCount > 0, "it tore")
        for (link in cloth.links) if (cs.isLinkAlive(link)) assertTrue(cs.strain(link) < 0.6f, "nothing left hanging on by a thread: link $link at ${cs.strain(link)}")
        // the pieces, through the links that are left
        val group = IntArray(ps.end) { it }
        fun root(p: Int): Int { var r = p; while (group[r] != r) r = group[r]; return r }
        for (link in cloth.links) if (cs.isLinkAlive(link)) group[root(cs.linkA(link))] = root(cs.linkB(link))
        val pieces = cloth.particles.map { root(it) }.toSet()
        assertEquals(2, pieces.size, "two halves, no scraps")
        assertTrue(root(cloth.particle(0, 0)) != root(cloth.particle(cols - 1, 0)), "split down the middle")
    }

    @Test
    fun aStrandOrClothRefusesWhatCannotWork() {
        val world = PhysicsWorld()
        assertFailsWith<IllegalArgumentException> { world.rope(0f, 0f, 10f, 0f, segments = 0) }
        assertFailsWith<IllegalArgumentException> { world.rope(5f, 5f, 5f, 5f, segments = 3) }
        assertFailsWith<IllegalArgumentException> { world.cloth(0f, 0f, cols = 1, rows = 5, spacing = 8f) }
        val rope = world.rope(0f, 0f, 10f, 0f, segments = 2)
        assertFailsWith<IllegalArgumentException> { world.constraints.setTear(rope.links[0], 0.5f) }
    }
}
