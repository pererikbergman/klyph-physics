package klyph.physics.demo

import klyph.entity.Entity
import klyph.entity.Transform
import klyph.entity.World
import klyph.physics.PhysicsConfig
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.SoftBodies
import klyph.physics.Vec
import klyph.physics.bridge.Physics
import klyph.physics.bridge.PhysicsBody
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bridge's [Physics], here because a test that links klyph needs the demo's SDL. Entities follow their bodies: a [Physics] step moves every linked [Transform] to where its body went. */
class PhysicsTest {
    private val dt = 1f / 60f
    private val entities = World()

    private fun linked(body: PhysicsBody, transform: Transform = Transform()): Entity {
        val entity = entities.create()
        entities.add(entity, Transform, transform)
        entities.add(entity, PhysicsBody, body)
        return entity
    }

    private fun transform(entity: Entity) = entities.get(entity, Transform)

    @Test
    fun aFallingSpinningBoxCarriesItsEntityWithIt() {
        val physics = Physics(PhysicsWorld())
        val bodies = physics.world.rigidBodies
        val box = physics.world.rigidBody(Shape.box(10f, 10f), 100f, 50f)
        bodies.setAngularVelocity(box, 2f)
        val crate = linked(PhysicsBody.rigid(box))
        repeat(30) { physics.step(entities, dt) }
        val t = transform(crate)
        assertTrue(t.y > 60f, "it fell: ${t.y}")
        assertEquals(bodies.x(box), t.x)
        assertEquals(bodies.y(box), t.y)
        assertEquals(bodies.angle(box), t.rotation)
        assertEquals(30L, physics.stepCount)
    }

    @Test
    fun aParticleCarriesItsEntityButDoesNotTurnIt() {
        val physics = Physics(PhysicsWorld())
        val p = physics.world.particles.add(10f, 10f)
        val dot = linked(PhysicsBody.particle(p), Transform(rotation = 0.5f))
        repeat(10) { physics.step(entities, dt) }
        assertEquals(physics.world.particles.y(p), transform(dot).y)
        assertEquals(0.5f, transform(dot).rotation)
    }

    @Test
    fun aSoftBodyCarriesItsEntityByItsMiddleAndTurnsItAsItRolls() {
        val physics = Physics(PhysicsWorld(PhysicsConfig(gravity = Vec(0f, 0f))))
        val world = physics.world
        val blob = world.softBody(SoftBodies.circle(radius = 10f, segments = 12, coreMass = 2f), 100f, 100f)
        val ball = linked(PhysicsBody.soft(blob))
        physics.step(entities, dt)
        assertEquals(world.softBodies.centerX(blob), transform(ball).x)
        assertEquals(world.softBodies.centerY(blob), transform(ball).y)
        assertTrue(abs(transform(ball).rotation) < 0.01f, "not turned yet: ${transform(ball).rotation}")
        // turned a quarter round, all at once
        world.softBodies.rotate(blob, (PI / 2).toFloat())
        physics.step(entities, dt)
        assertTrue(abs(transform(ball).rotation - (PI / 2).toFloat()) < 0.05f, "a quarter turn: ${transform(ball).rotation}")
    }

    @Test
    fun aRemovedBodyLeavesItsEntityWhereItWas() {
        val physics = Physics(PhysicsWorld())
        val box = physics.world.rigidBody(Shape.box(10f, 10f), 100f, 50f)
        val crate = linked(PhysicsBody.rigid(box))
        repeat(5) { physics.step(entities, dt) }
        val y = transform(crate).y
        physics.world.rigidBodies.remove(box)
        repeat(5) { physics.step(entities, dt) }
        assertEquals(y, transform(crate).y)
    }

    @Test
    fun entitiesWithoutABodyAreLeftAlone() {
        val physics = Physics(PhysicsWorld())
        physics.world.rigidBody(Shape.box(10f, 10f), 100f, 50f)
        val still = entities.create()
        entities.add(still, Transform, Transform(x = 7f, y = 8f))
        repeat(5) { physics.step(entities, dt) }
        assertEquals(7f, transform(still).x)
        assertEquals(8f, transform(still).y)
    }

    @Test
    fun aSwappedWorldIsTheOneThatSteps() {
        val physics = Physics(PhysicsWorld())
        val first = physics.world
        val next = PhysicsWorld()
        val box = next.rigidBody(Shape.box(10f, 10f), 100f, 50f)
        physics.world = next
        physics.step(entities, dt)
        assertTrue(next.rigidBodies.y(box) > 50f, "the new world's box fell")
        assertEquals(0, first.rigidBodies.count)
    }
}
