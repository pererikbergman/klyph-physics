package klyph.physics

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Puts still rigid bodies to sleep, a whole island at a time. An island is every dynamic body that
 * touches or is joined to another, to another, and so on: a pile, a stack, a hanging chain. Static
 * bodies and the solids join nothing, or the whole level would be one island.
 *
 * After each step, every awake dynamic body slower than [PhysicsConfig.sleepSpeed] adds the step to
 * how long it has been still, and anything faster starts it again from 0. An island whose bodies have
 * all been still for [PhysicsConfig.timeToSleep] falls asleep together: see [RigidBodies.wake] for
 * what wakes it again.
 *
 * Some bodies never sleep, because something outside the islands moves them: one that is grabbed,
 * tied to a particle, driven by a motor, or touching a kinematic body or a moving solid; and one
 * without shapes, as nothing could touch it to wake it.
 *
 * It finds the islands with union-find over the last substep's contacts and the joints, in arrays
 * kept from step to step, so it allocates nothing once they're big enough.
 */
internal class Sleeping(private val world: PhysicsWorld) {
    private val bodies = world.rigidBodies

    // Each body's parent in the union-find, and for an island's root, the least time any of its bodies has been still.
    private var parent = IntArray(16)
    private var islandStill = FloatArray(16)

    /** Looks at every awake body after a step of [dt] seconds, and puts each island that's been still long enough to sleep. */
    fun update(dt: Float) {
        val config = world.config
        if (config.timeToSleep == Float.POSITIVE_INFINITY) return
        val bs = bodies
        val n = bs.end
        if (n == 0) return
        if (parent.size < n) {
            parent = IntArray(maxOf(n, 2 * parent.size))
            islandStill = FloatArray(parent.size)
        }
        var awake = 0
        for (b in 0 until n) {
            parent[b] = b
            if (!bs.alive[b] || bs.kind[b] != RigidBodies.DYNAMIC || bs.asleep[b]) continue
            awake++
            // nothing could ever touch a body without shapes to wake it, and it costs nothing awake
            if (bs.reach[b] == 0f) {
                bs.stillFor[b] = 0f
                continue
            }
            val speed = sqrt(bs.vx[b] * bs.vx[b] + bs.vy[b] * bs.vy[b]) + abs(bs.omega[b]) * bs.reach[b]
            bs.stillFor[b] = if (speed < config.sleepSpeed) bs.stillFor[b] + dt else 0f
        }
        if (awake == 0) return
        keepAwake()
        val contacts = world.rigidContacts
        for (c in 0 until contacts.count) {
            val p = contacts.bodyA(c)
            val q = contacts.bodyB(c)
            if (q < 0) {
                if (contacts.isAgainstMovingSolid(c)) bs.stillFor[p] = 0f
                continue
            }
            join(p, q)
        }
        val joints = world.joints
        for (j in 0 until joints.end) {
            if (!joints.isAlive(j)) continue
            val q = joints.endB(j)
            if (q >= 0) join(joints.bodyA(j), q)
        }
        for (b in 0 until n) islandStill[b] = Float.MAX_VALUE
        for (b in 0 until n) {
            if (!isAwakeDynamic(b)) continue
            val root = find(b)
            islandStill[root] = min(islandStill[root], bs.stillFor[b])
        }
        for (b in 0 until n) {
            if (!isAwakeDynamic(b)) continue
            val root = find(b)
            if (islandStill[root] >= config.timeToSleep) bs.sleep(b, root)
        }
    }

    /** Starts the still time again of every body something outside the islands moves. */
    private fun keepAwake() {
        val bs = bodies
        val grabbed = world.grabbedBody
        if (grabbed >= 0) bs.stillFor[grabbed] = 0f
        val tied = world.attachments
        for (a in 0 until tied.end) if (tied.isAlive(a)) bs.stillFor[tied.body(a)] = 0f
        val joints = world.joints
        for (j in 0 until joints.end) {
            if (!joints.isAlive(j) || !joints.keepsAwake(j)) continue
            bs.stillFor[joints.bodyA(j)] = 0f
            if (joints.endB(j) >= 0) bs.stillFor[joints.endB(j)] = 0f
        }
    }

    private fun isAwakeDynamic(b: Int): Boolean = bodies.alive[b] && bodies.kind[b] == RigidBodies.DYNAMIC && !bodies.asleep[b]

    /**
     * Puts [p] and [q] in one island, if both are awake and dynamic. Touching a kinematic body, a
     * dynamic one isn't joined to it, but kept awake: the kinematic one can move off at any time.
     */
    private fun join(p: Int, q: Int) {
        val bs = bodies
        val kp = bs.kind[p]
        val kq = bs.kind[q]
        if (kp == RigidBodies.KINEMATIC) bs.stillFor[q] = 0f
        if (kq == RigidBodies.KINEMATIC) bs.stillFor[p] = 0f
        if (!isAwakeDynamic(p) || !isAwakeDynamic(q)) return
        val rp = find(p)
        val rq = find(q)
        if (rp != rq) parent[rp] = rq
    }

    /** The root of [b]'s island, halving the path to it on the way. */
    private fun find(b: Int): Int {
        var x = b
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
}
