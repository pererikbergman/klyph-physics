package klyph.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParticlesTest {
    private val particles = Particles(initialCapacity = 2)

    @Test
    fun aNewParticleIsAliveAtRestWhereItWasPut() {
        val p = particles.add(3f, 4f, mass = 2f, radius = 1.5f)
        assertTrue(particles.isAlive(p))
        assertEquals(3f, particles.x(p))
        assertEquals(4f, particles.y(p))
        assertEquals(0f, particles.movedX(p))
        assertEquals(0.5f, particles.inverseMass(p))
        assertEquals(1.5f, particles.radius(p))
        assertEquals(1, particles.count)
    }

    @Test
    fun theStoreGrowsByDoublingAndKeepsWhatItHad() {
        val handles = (0 until 100).map { particles.add(it.toFloat(), -it.toFloat()) }
        assertEquals((0 until 100).toList(), handles)
        for (p in handles) {
            assertEquals(p.toFloat(), particles.x(p))
            assertEquals(-p.toFloat(), particles.y(p))
        }
        assertEquals(100, particles.count)
    }

    @Test
    fun aRemovedHandleIsHandedOutAgain() {
        val a = particles.add(0f, 0f)
        val b = particles.add(1f, 0f)
        particles.remove(a)
        assertFalse(particles.isAlive(a))
        assertEquals(1, particles.count)
        val c = particles.add(5f, 5f)
        assertEquals(a, c, "the free handle is reused")
        assertTrue(particles.isAlive(c))
        assertEquals(5f, particles.x(c))
        assertEquals(2, particles.end, "no new slot was needed")
        assertTrue(particles.isAlive(b))
    }

    @Test
    fun removingADeadParticleTwiceDoesNotHandItOutTwice() {
        val a = particles.add(0f, 0f)
        particles.remove(a)
        particles.remove(a)
        val b = particles.add(0f, 0f)
        val c = particles.add(0f, 0f)
        assertTrue(b != c)
    }

    @Test
    fun aStaticParticleHasNoInverseMass() {
        val p = particles.add(0f, 0f, mass = Particles.STATIC)
        assertEquals(0f, particles.inverseMass(p))
    }

    @Test
    fun aParticleNeedsAMassAndARadiusThatMakeSense() {
        assertFailsWith<IllegalArgumentException> { particles.add(0f, 0f, mass = 0f) }
        assertFailsWith<IllegalArgumentException> { particles.add(0f, 0f, radius = -1f) }
    }

    @Test
    fun movingKeepsTheVelocityAndSettingTheVelocityKeepsThePosition() {
        val p = particles.add(0f, 0f)
        particles.setVelocity(p, 240f, -480f, h = 1f / 240f)
        assertEquals(1f, particles.movedX(p), 0.0001f)
        assertEquals(-2f, particles.movedY(p), 0.0001f)
        particles.moveTo(p, 10f, 10f)
        assertEquals(10f, particles.x(p))
        assertEquals(1f, particles.movedX(p), 0.0001f)
        assertEquals(-2f, particles.movedY(p), 0.0001f)
    }

    @Test
    fun clearingStartsTheHandlesAgain() {
        particles.add(0f, 0f)
        particles.add(0f, 0f)
        particles.clear()
        assertEquals(0, particles.count)
        assertEquals(0, particles.add(0f, 0f))
    }
}
