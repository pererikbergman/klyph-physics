package klyph.physics.demo

import klyph.physics.PhysicsWorld
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The mouse in every scene: the left button grabs and drags, the right one spawns. */
class MouseTest {
    private val dt = 16_666_666 / 1e9f
    private val roomWidth = Scenes.COLS * Scenes.TILE
    private val roomHeight = Scenes.ROWS * Scenes.TILE

    /** The particle that can move nearest the middle of the room: something to aim the mouse at. */
    private fun movableNearTheMiddle(world: PhysicsWorld): Int {
        val ps = world.particles
        var best = -1
        var bestSquared = Float.MAX_VALUE
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p) || ps.inverseMass(p) == 0f) continue
            val dx = ps.x(p) - roomWidth / 2f
            val dy = ps.y(p) - roomHeight / 2f
            if (dx * dx + dy * dy < bestSquared) {
                best = p
                bestSquared = dx * dx + dy * dy
            }
        }
        return best
    }

    private fun assertAllFinite(name: String, world: PhysicsWorld) {
        val ps = world.particles
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p)) continue
            assertTrue(ps.x(p).isFinite() && ps.y(p).isFinite(), "$name: particle $p went to (${ps.x(p)}, ${ps.y(p)})")
        }
    }

    @Test
    fun inEverySceneTheMouseGrabsSomethingAndFlingingItAboutBreaksNothing() {
        val sandbox = Sandbox(Scenes.all)
        for (i in 0 until sandbox.sceneCount) {
            sandbox.select(i)
            val name = sandbox.scene.name
            repeat(60) { sandbox.fixedStep(dt) }
            val world = sandbox.run.world ?: fail("$name has no world to grab in")
            val ps = world.particles
            val p = movableNearTheMiddle(world)
            // a scene of rigid bodies only: the hand holds particles, not those yet
            if (p < 0) continue
            assertTrue(sandbox.grab(ps.x(p), ps.y(p)), "$name: nothing grabbed at particle $p")
            // a mouse thrown about the whole window, somewhere new every frame
            val random = Random(i)
            repeat(240) {
                sandbox.moveGrab(random.nextFloat() * roomWidth, random.nextFloat() * roomHeight)
                sandbox.fixedStep(dt)
                assertAllFinite(name, world)
            }
            sandbox.release()
            assertEquals(-1, world.grabbed, "$name: let go")
            repeat(180) { sandbox.fixedStep(dt) }
            assertAllFinite(name, world)
            val bodies = world.softBodies
            for (body in 0 until bodies.end) {
                if (!bodies.isAlive(body)) continue
                // 2.5, not 1: squeezed at the bottom of a pile, a body can be held stretched to twice its
                // edges by friction, grabbed or not (see the pile's own test in CollisionTest)
                assertTrue(bodies.maxEdgeStretch(body) < 2.5f, "$name: body $body is still torn, ${bodies.maxEdgeStretch(body)}")
                val x = bodies.centerX(body)
                val y = bodies.centerY(body)
                assertTrue(x in 0f..roomWidth && y in 0f..roomHeight, "$name: body $body was dragged out of the room, to ($x, $y)")
            }
        }
    }

    @Test
    fun aRightClickAddsSomethingInEveryScene() {
        val sandbox = Sandbox(Scenes.all)
        for (i in 0 until sandbox.sceneCount) {
            sandbox.select(i)
            sandbox.fixedStep(dt)
            val world = sandbox.run.world ?: fail("${sandbox.scene.name} has no world")
            val before = world.particles.count + world.rigidBodies.count
            sandbox.spawn(roomWidth / 2f, 60f)
            assertTrue(world.particles.count + world.rigidBodies.count > before, "${sandbox.scene.name}: nothing spawned")
            repeat(120) { sandbox.fixedStep(dt) }
            assertAllFinite(sandbox.scene.name, world)
        }
    }

    @Test
    fun nothingIsSpawnedInsideAWallAndNoMoreThanTheLimit() {
        val sandbox = Sandbox(Scenes.all)
        sandbox.select(Scenes.all.indexOf(RopeSwingScene))
        val world = sandbox.run.world!!
        val bodies = world.softBodies.count
        sandbox.spawn(8f, 100f)
        assertEquals(bodies, world.softBodies.count, "not inside the left wall")
        repeat(100) { sandbox.spawn(40f + it % 30 * 18f, 40f) }
        assertEquals(bodies + Scenes.MAX_SPAWNED, world.softBodies.count)
    }

    @Test
    fun theScriptedHandGrabsLiftsSwingsAndLetsGo() {
        assertNull(ScriptedHand.parse(null))
        assertNull(ScriptedHand.parse("320"))
        assertNull(ScriptedHand.parse("a,b"))
        assertEquals(320f, ScriptedHand.parse(" 320 , 200 ")!!.x)
        val sandbox = Sandbox(Scenes.all)
        sandbox.select(Scenes.all.indexOf(ClothScene))
        // the curtain's free bottom edge, which can be lifted
        val hand = ScriptedHand(280f, 216f)
        var heldAt100 = -1
        var lowestY = Float.MAX_VALUE
        while (sandbox.stepCount < ScriptedHand.RELEASE_AT + 30) {
            hand.drive(sandbox)
            sandbox.fixedStep(dt)
            val world = sandbox.run.world!!
            if (sandbox.stepCount == 100L) heldAt100 = world.grabbed
            if (world.grabbed >= 0) lowestY = minOf(lowestY, world.particles.y(world.grabbed))
        }
        assertTrue(heldAt100 >= 0, "it held something")
        assertTrue(lowestY < 216f - ScriptedHand.LIFT / 2f, "and lifted it: up to $lowestY")
        assertEquals(-1, sandbox.run.world!!.grabbed, "and let go")
    }
}
