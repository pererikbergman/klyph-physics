package klyph.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fast things against thin walls: bullets, fast bodies and fast particles never pass through. */
class TunnellingTest {
    private val dt = TestBodies.DT

    /** No gravity, no damping: a thing thrown goes straight on at the speed it was given. */
    private fun space() = PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f), damping = 0f))

    @Test
    fun aBulletAtFiveThousandUnitsASecondNeverPassesAOneUnitWall() {
        val random = Random(32)
        repeat(1000) { attempt ->
            val world = space()
            val bodies = world.rigidBodies
            // a wall one unit thick through (500, 300), turned any way, and long enough that nothing
            // sliding along it gets round its end in the time
            val wallAngle = random.nextFloat() * PI.toFloat()
            val wall = world.rigidBody(Shape.box(1f, 4000f), 500f, 300f, angle = wallAngle, kind = BodyKind.STATIC)
            val nx = cos(wallAngle)
            val ny = sin(wallAngle)
            // a small bullet of any of the three kinds
            val shape = when (attempt % 3) {
                0 -> Shape.circle(1f + random.nextFloat() * 3f)
                1 -> Shape.box(2f + random.nextFloat() * 4f, 1f + random.nextFloat() * 2f)
                else -> Shape.capsule(2f + random.nextFloat() * 4f, 0.5f + random.nextFloat())
            }
            // aimed at a point on the wall's middle 160, a little off square, from 150 to 400 away
            val side = if (random.nextBoolean()) 1f else -1f
            val back = 150f + random.nextFloat() * 250f
            val along = (random.nextFloat() - 0.5f) * 160f
            val hitX = 500f - ny * along
            val hitY = 300f + nx * along
            val aim = (random.nextFloat() - 0.5f) * 1.2f
            val dirX = -side * (nx * cos(aim) - ny * sin(aim))
            val dirY = -side * (ny * cos(aim) + nx * sin(aim))
            val bullet = world.rigidBody(shape, hitX - dirX * back, hitY - dirY * back, angle = random.nextFloat() * 6f)
            bodies.setBullet(bullet, true)
            bodies.setVelocity(bullet, dirX * 5000f, dirY * 5000f)
            bodies.setAngularVelocity(bullet, (random.nextFloat() - 0.5f) * 60f)
            repeat(20) {
                world.step(dt)
                val stillInFront = (bodies.x(bullet) - 500f) * nx + (bodies.y(bullet) - 300f) * ny
                assertTrue(stillInFront * side > 0f, "try $attempt: the bullet went through the wall, to $stillInFront on its far side")
            }
            assertTrue(bodies.isAlive(wall))
        }
    }

    @Test
    fun aBulletReallyGoesFiveThousandUnitsASecond() {
        val world = space()
        val bodies = world.rigidBodies
        val bullet = world.rigidBody(Shape.circle(2f), 0f, 0f)
        bodies.setBullet(bullet, true)
        bodies.setVelocity(bullet, 5000f, 0f)
        repeat(6) { world.step(dt) }
        assertEquals(500f, bodies.x(bullet), 1f, "a tenth of a second at 5 000 units a second")
        val slow = world.rigidBody(Shape.circle(2f), 0f, 100f)
        bodies.setVelocity(slow, 5000f, 0f)
        repeat(6) { world.step(dt) }
        assertTrue(bodies.x(slow) < 100f, "a body that isn't a bullet is held to maxStep a substep: ${bodies.x(slow)}")
    }

    @Test
    fun aBulletBouncesOffAWallAsItsRestitutionSays() {
        val world = space()
        val bodies = world.rigidBodies
        world.rigidBody(Shape.box(1f, 200f), 500f, 300f, kind = BodyKind.STATIC)
        val bullet = world.rigidBody(Shape.circle(2f), 200f, 300f, material = Material(restitution = 1f))
        bodies.setBullet(bullet, true)
        bodies.setVelocity(bullet, 3000f, 0f)
        repeat(12) { world.step(dt) }
        assertTrue(bodies.x(bullet) < 497.5f, "on its own side: ${bodies.x(bullet)}")
        assertTrue(bodies.velocityX(bullet) < -2500f, "and on its way back, as fast: ${bodies.velocityX(bullet)}")
    }

    @Test
    fun aBulletHitsAThinDynamicBodyAndKnocksItOn() {
        val world = space()
        val bodies = world.rigidBodies
        val plate = world.rigidBody(Shape.box(2f, 60f), 500f, 300f)
        val bullet = world.rigidBody(Shape.circle(2f), 100f, 300f, density = 1f)
        bodies.setBullet(bullet, true)
        bodies.setVelocity(bullet, 5000f, 0f)
        repeat(10) { world.step(dt) }
        assertTrue(bodies.x(bullet) < bodies.x(plate), "the bullet is still behind the plate: ${bodies.x(bullet)}, plate at ${bodies.x(plate)}")
        assertTrue(bodies.velocityX(plate) > 50f, "and knocked it on: ${bodies.velocityX(plate)}")
    }

    @Test
    fun aBulletStopsAtTheSolids() {
        val world = space()
        val bodies = world.rigidBodies
        world.solids = SolidGrid(40, 30, 16f).apply { for (r in 0 until 30) fill(30, r) }
        val bullet = world.rigidBody(Shape.box(4f, 2f), 100f, 200f)
        bodies.setBullet(bullet, true)
        bodies.setVelocity(bullet, 5000f, 20f)
        repeat(30) { world.step(dt) }
        assertTrue(bodies.x(bullet) < 30 * 16f, "it stopped at the wall's face: ${bodies.x(bullet)}")
    }

    @Test
    fun aBulletRollsAlongTheFloorWithoutCatchingOnIt() {
        val world = PhysicsWorld()
        val bodies = world.rigidBodies
        world.solids = SolidGrid(200, 30, 16f).apply { for (c in 0 until cols) fill(c, 28) }
        val ball = world.rigidBody(Shape.circle(6f), 100f, 28 * 16f - 6f)
        bodies.setBullet(ball, true)
        repeat(30) { world.step(dt) }
        bodies.setVelocity(ball, 1500f, 0f)
        repeat(30) { world.step(dt) }
        assertTrue(bodies.x(ball) > 700f, "a bullet resting on the floor rolls along it freely: ${bodies.x(ball)}")
        assertEquals(28 * 16f - 6f, bodies.y(ball), 1f, "on the floor")
    }

    @Test
    fun aFastBodyThatIsntABulletStillDoesntPassAOneUnitWall() {
        val random = Random(33)
        repeat(200) { attempt ->
            val world = space()
            val bodies = world.rigidBodies
            world.rigidBody(Shape.box(1f, 200f), 500f, 300f, kind = BodyKind.STATIC)
            val radius = 1f + random.nextFloat() * 3f
            val ball = world.rigidBody(Shape.circle(radius), 300f + random.nextFloat() * 100f, 300f + (random.nextFloat() - 0.5f) * 100f)
            // as fast as maxStep lets it: 4 a substep
            bodies.setVelocity(ball, 5000f, (random.nextFloat() - 0.5f) * 1000f)
            repeat(20) {
                world.step(dt)
                assertTrue(bodies.x(ball) < 500f, "try $attempt: a ball of radius $radius went through, to ${bodies.x(ball)}")
            }
        }
    }

    @Test
    fun aFastParticleNeverPassesAThinSolid() {
        val random = Random(34)
        repeat(200) { attempt ->
            val world = space()
            // a ledge half a tile thick: the thinnest solid there is
            world.solids = SolidGrid(40, 30, 16f).apply { for (c in 0 until cols) fillTopHalf(c, 15) }
            val ps = world.particles
            val p = ps.add(100f + random.nextFloat() * 400f, 100f + random.nextFloat() * 50f, radius = random.nextFloat() * 2f)
            ps.setBullet(p, true)
            world.setVelocity(p, (random.nextFloat() - 0.5f) * 2000f, 5000f)
            repeat(10) {
                world.step(dt)
                assertTrue(ps.y(p) < 15 * 16f, "try $attempt: the particle went through the ledge, to ${ps.y(p)}")
            }
        }
    }

    @Test
    fun aBulletParticleReallyGoesFast() {
        val world = space()
        val ps = world.particles
        val p = ps.add(0f, 0f)
        ps.setBullet(p, true)
        world.setVelocity(p, 5000f, 0f)
        repeat(6) { world.step(dt) }
        assertEquals(500f, ps.x(p), 2f)
    }
}
