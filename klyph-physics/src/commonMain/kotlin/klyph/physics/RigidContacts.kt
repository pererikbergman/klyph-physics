package klyph.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps rigid bodies out of each other and out of the solids, with friction and bounce: XPBD contacts,
 * as in Müller et al. 2020.
 *
 * Once per step, [beginStep] files every body in a [Broadphase], grown by how far it will move. Each
 * substep, after the bodies have moved, [find] makes a contact for every point where two bodies'
 * shapes touch or nearly do, and where a body meets the edges of the solids. A contact remembers its
 * two points, each on its own body, and its normal, for the rest of the substep. Then every pass,
 * [solve] pushes each pair of points apart along the normal by however far they overlap, split by how
 * hard each body is to move there, and holds them together sideways (static friction) while the push
 * along the surface it would take is under the static friction times the push holding them on. After
 * the substep, [solveVelocities] takes off sliding speed for dynamic friction, and sets the speed out
 * of the surface for restitution.
 *
 * Read it to draw the contacts of the last substep: [count] of them, each with a point, a normal and
 * how hard it pushed.
 */
class RigidContacts internal constructor(private val world: PhysicsWorld) {
    private val bodies = world.rigidBodies
    private val narrow = Narrowphase().apply { margin = MARGIN }
    private val manifold = Manifold()
    internal val edges = SolidEdges()

    /** Every rigid body of this step, by its box grown by its move: a box's id is the body. */
    val broadphase = Broadphase(world.config.cellSize)

    /** How many contacts the last substep found. */
    var count = 0
        private set

    // Each contact: body A and body B (-1 for a solid's edge), each point in its own body's frame (a
    // world point for an edge), the normal from A to B, the pushes so far this substep, the grip and
    // bounce, the speed into each other it started with, and its friction anchors: see [add].
    private var a = IntArray(64)
    private var b = IntArray(64)
    private var r1x = FloatArray(64)
    private var r1y = FloatArray(64)
    private var r2x = FloatArray(64)
    private var r2y = FloatArray(64)
    private var nx = FloatArray(64)
    private var ny = FloatArray(64)
    private var lambdaN = FloatArray(64)
    private var lambdaT = FloatArray(64)
    private var grip = FloatArray(64)
    private var hold = FloatArray(64)
    private var bounce = FloatArray(64)
    private var speedIn = FloatArray(64)
    private var f1x = FloatArray(64)
    private var f1y = FloatArray(64)
    private var f2x = FloatArray(64)
    private var f2y = FloatArray(64)

    // What makes a contact the same one next substep: besides its two bodies, which of their shapes
    // touch, and which corner and face of them; see [Manifold.id]. And whether its grip slipped.
    private var shapeKey = IntArray(64)
    private var feature = IntArray(64)
    private var slipped = BooleanArray(64)

    // The last substep's contacts, as much of them as [add] needs to carry their anchors on, and a
    // table to find one by what makes it the same: open addressing, each slot a contact plus one, 0 for empty.
    private var oldCount = 0
    private var oldA = IntArray(64)
    private var oldB = IntArray(64)
    private var oldShapeKey = IntArray(64)
    private var oldFeature = IntArray(64)
    private var oldF1x = FloatArray(64)
    private var oldF1y = FloatArray(64)
    private var oldF2x = FloatArray(64)
    private var oldF2y = FloatArray(64)
    private var oldHeld = BooleanArray(64)
    private var table = IntArray(128)

    // The other point of a two-point contact, solved together with this one, or -1.
    private var twin = IntArray(64)

    // How hard the velocity pass's dynamic friction has struck each contact so far this substep, along
    // its tangent: a pair's on its first point.
    private var slip = FloatArray(64)


    /** Where contact [c]'s point on its first body is in the world now. */
    fun pointX(c: Int): Float = bodies.worldX(a[c], r1x[c], r1y[c])

    fun pointY(c: Int): Float = bodies.worldY(a[c], r1x[c], r1y[c])

    /** Contact [c]'s normal: the way its first body would be pushed into the second, so it's pushed back the other way. */
    fun normalX(c: Int): Float = nx[c]

    fun normalY(c: Int): Float = ny[c]

    /**
     * How hard contact [c] pushed in the last substep, as mass times distance: 0 for one that was found
     * near, but never touched.
     */
    fun push(c: Int): Float = lambdaN[c]

    /**
     * Whether contact [c] slid in the last substep: friction couldn't hold it, so it slips along its
     * surface. A two-point contact slides or holds as one.
     */
    fun isSliding(c: Int): Boolean = slipped[c] || (twin[c] >= 0 && slipped[twin[c]])

    /** The two bodies of contact [c]: [bodyB] is -1 where the first meets the solids. */
    fun bodyA(c: Int): Int = a[c]

    fun bodyB(c: Int): Int = b[c]

    /** Files every body with a shape in the [broadphase], each grown by how far it will move in [dt] seconds. */
    internal fun beginStep(dt: Float) {
        val bp = broadphase
        bp.clear()
        val bs = bodies
        for (body in 0 until bs.end) {
            if (!bs.isAlive(body) || bs.shapes[body]!!.isEmpty()) continue
            val x = bs.x[body]
            val y = bs.y[body]
            val r = bs.reach[body] + MARGIN
            val dx = bs.vx[body] * dt
            val dy = bs.vy[body] * dt
            // turning, the shapes sweep out no further than their reach
            bp.add(body, min(x, x + dx) - r, min(y, y + dy) - r, maxOf(x, x + dx) + r, maxOf(y, y + dy) + r)
        }
        if (bp.count > 1) bp.build()
    }

    /** Finds every contact for this substep, with the bodies where they are now. */
    internal fun find() {
        remember()
        count = 0
        val bp = broadphase
        for (i in 0 until bp.pairCount) bodyAgainstBody(bp.pairA(i), bp.pairB(i))
        findAgainstSolids()
    }

    /**
     * Keeps what the last substep's contacts need to carry their friction anchors on, and files them
     * by what makes them the same. A contact that pushed and didn't slip held on: the same contact
     * found again holds on at the same two points.
     */
    private fun remember() {
        val n = count
        if (oldA.size < n) {
            val size = a.size
            oldA = IntArray(size)
            oldB = IntArray(size)
            oldShapeKey = IntArray(size)
            oldFeature = IntArray(size)
            oldF1x = FloatArray(size)
            oldF1y = FloatArray(size)
            oldF2x = FloatArray(size)
            oldF2y = FloatArray(size)
            oldHeld = BooleanArray(size)
        }
        var size = table.size
        while (size < 2 * n) size *= 2
        if (size != table.size) table = IntArray(size) else table.fill(0)
        val mask = size - 1
        for (c in 0 until n) {
            oldA[c] = a[c]
            oldB[c] = b[c]
            oldShapeKey[c] = shapeKey[c]
            oldFeature[c] = feature[c]
            oldF1x[c] = f1x[c]
            oldF1y[c] = f1y[c]
            oldF2x[c] = f2x[c]
            oldF2y[c] = f2y[c]
            val t = twin[c]
            oldHeld[c] = (lambdaN[c] > 0f || t >= 0 && lambdaN[t] > 0f) && !slipped[c]
            var slot = hash(a[c], b[c], shapeKey[c], feature[c]) and mask
            while (table[slot] != 0) slot = (slot + 1) and mask
            table[slot] = c + 1
        }
        oldCount = n
    }

    /** The last substep's contact between [p] and [q] by the same shapes and feature, or -1. */
    private fun recall(p: Int, q: Int, shapes: Int, id: Int): Int {
        if (oldCount == 0) return -1
        val mask = table.size - 1
        var slot = hash(p, q, shapes, id) and mask
        while (true) {
            val k = table[slot] - 1
            if (k < 0) return -1
            if (oldA[k] == p && oldB[k] == q && oldShapeKey[k] == shapes && oldFeature[k] == id) return k
            slot = (slot + 1) and mask
        }
    }

    private fun hash(p: Int, q: Int, shapes: Int, id: Int): Int =
        (p * 73_856_093) xor (q * 19_349_663) xor (shapes * 83_492_791) xor (id * -1_640_531_535)

    private fun findAgainstSolids() {
        val grid = world.solids ?: return
        // rebuilt, the edges are numbered afresh: an old edge contact's number means nothing now
        if (edges.update(grid)) forgetEdges()
        for (body in 0 until bodies.end) {
            if (!bodies.isAlive(body) || bodies.kind[body] != RigidBodies.DYNAMIC) continue
            val reach = bodies.reach[body] + MARGIN
            val x = bodies.x[body]
            val y = bodies.y[body]
            edges.near(x - reach, y - reach, x + reach, y + reach)
            prevCos = cos(bodies.prevAngle[body])
            prevSin = sin(bodies.prevAngle[body])
            for (k in 0 until edges.foundCount) bodyAgainstEdge(body, edges.found[k])
            val moving = grid.dynamic
            for (k in 0 until moving.size) bodyAgainstSolid(body, moving[k], k)
        }
    }

    private fun bodyAgainstBody(p: Int, q: Int) {
        if (!bodies.isAlive(p) || !bodies.isAlive(q)) return
        if (bodies.invMass[p] == 0f && bodies.invMass[q] == 0f) return
        val shapesP = bodies.shapes[p]!!
        val shapesQ = bodies.shapes[q]!!
        for (i in shapesP.indices) for (j in shapesQ.indices) {
            val sp = shapesP[i]
            val sq = shapesQ[j]
            if (!narrow.collide(sp, bodies.x[p], bodies.y[p], bodies.cos[p], bodies.sin[p], sq, bodies.x[q], bodies.y[q], bodies.cos[q], bodies.sin[q], manifold)) continue
            val m = manifold
            for (k in 0 until m.count) {
                add(p, q, m.ax[k], m.ay[k], m.bx[k], m.by[k], m.normalX, m.normalY,
                    Material.mixFriction(bodies.friction[p], bodies.friction[q]),
                    Material.mixFriction(bodies.staticFriction[p], bodies.staticFriction[q]),
                    Material.mixRestitution(bodies.restitution[p], bodies.restitution[q]),
                    shapes = (i shl 16) or j, id = m.ids[k])
            }
            if (m.count == 2) pair(count - 2, count - 1)
        }
    }

    // The turn of the body [bodyAgainstEdge] is looking at, as it was at the start of the substep.
    private var prevCos = 1f
    private var prevSin = 0f

    /**
     * Body [body] against the one-sided edge [e] of the solids: every point of its shapes' cores that
     * reaches through the edge, between its ends, and the corner the edge starts at, if that's a
     * convex one, wherever it reaches into the body. Where the outline turns inwards at an end, the
     * solid goes on past it, so a point a little past that end, pressed into the corner, counts too.
     *
     * A point more than [MAX_DEPTH] behind the edge has come through the solid from its far side, and
     * is left to that side's edge, unless it was nearer at the start of the substep: then it has been
     * driven in deep from this side, and is pushed back out this way.
     */
    private fun bodyAgainstEdge(body: Int, e: Int) {
        val ed = edges
        val ex1 = ed.x1[e]
        val ey1 = ed.y1[e]
        val enx = ed.nx[e]
        val eny = ed.ny[e]
        val tx = -eny
        val ty = enx
        val length = (ed.x2[e] - ex1) * tx + (ed.y2[e] - ey1) * ty
        val c = bodies.cos[body]
        val s = bodies.sin[body]
        val x = bodies.x[body]
        val y = bodies.y[body]
        val friction = Material.mixFriction(bodies.friction[body], ed.friction[e])
        val staticFriction = Material.mixFriction(bodies.staticFriction[body], ed.staticFriction[e])
        val restitution = Material.mixRestitution(bodies.restitution[body], ed.restitution[e])
        val shapes = bodies.shapes[body]!!
        for (si in shapes.indices) {
            val shape = shapes[si]
            val r = shape.radius
            val first = count
            for (i in 0 until shape.vertexCount) {
                val vx = x + c * shape.vx[i] - s * shape.vy[i]
                val vy = y + s * shape.vx[i] + c * shape.vy[i]
                val along = (vx - ex1) * tx + (vy - ey1) * ty
                if (along < (if (ed.solidPastStart[e]) -MAX_DEPTH else 0f) || along > length + (if (ed.solidPastEnd[e]) MAX_DEPTH else 0f)) continue
                val out = (vx - ex1) * enx + (vy - ey1) * eny
                val separation = out - r
                if (separation > MARGIN) continue
                if (out < -MAX_DEPTH) {
                    val px = bodies.prevX[body] + prevCos * shape.vx[i] - prevSin * shape.vy[i]
                    val py = bodies.prevY[body] + prevSin * shape.vx[i] + prevCos * shape.vy[i]
                    if ((px - ex1) * enx + (py - ey1) * eny < -MAX_DEPTH) continue
                }
                add(body, -1, vx - enx * r, vy - eny * r, vx - enx * out, vy - eny * out, -enx, -eny, friction, staticFriction, restitution,
                    shapes = si shl 16, id = ON_EDGE or (e shl 8) or i)
            }
            // a face lying on the edge: its two ends hold it up together
            if (count - first == 2) pair(first, first + 1)
            if (ed.convexStart[e]) cornerAgainstShape(body, shape, si, e, ex1, ey1, friction, staticFriction, restitution)
        }
    }

    /**
     * The convex corner at ([qx], [qy]) where edge [e] starts, against [shape] of [body]: if the
     * corner reaches into the shape, a contact pushing the body off it, but only out into the open,
     * between the two edges' normals.
     */
    private fun cornerAgainstShape(body: Int, shape: Shape, si: Int, e: Int, qx: Float, qy: Float, friction: Float, staticFriction: Float, restitution: Float) {
        val lx = bodies.localX(body, qx, qy)
        val ly = bodies.localY(body, qx, qy)
        val n = shape.vertexCount
        // the way the body is pushed, in its own frame, and how far the corner is outside it
        var outX: Float
        var outY: Float
        var separation: Float
        var deepest = -Float.MAX_VALUE
        var face = -1
        if (n >= 3) {
            for (i in 0 until n) {
                val d = (lx - shape.vx[i]) * shape.nx[i] + (ly - shape.vy[i]) * shape.ny[i]
                if (d > deepest) {
                    deepest = d
                    face = i
                }
            }
        }
        if (face >= 0 && deepest <= 0f) {
            // inside the polygon: out through its nearest face, so the body moves against that face's normal
            outX = -shape.nx[face]
            outY = -shape.ny[face]
            separation = deepest - shape.radius
        } else {
            // outside the core: from its nearest point
            var best = Float.MAX_VALUE
            var cx = 0f
            var cy = 0f
            val edgesOfShape = if (n == 2) 1 else n
            for (i in 0 until edgesOfShape) {
                val j = if (i + 1 == n) 0 else i + 1
                val ax = shape.vx[i]
                val ay = shape.vy[i]
                val sx = shape.vx[j] - ax
                val sy = shape.vy[j] - ay
                val ll = sx * sx + sy * sy
                val t = if (ll == 0f) 0f else (((lx - ax) * sx + (ly - ay) * sy) / ll).coerceIn(0f, 1f)
                val px = ax + sx * t
                val py = ay + sy * t
                val d = (lx - px) * (lx - px) + (ly - py) * (ly - py)
                if (d < best) {
                    best = d
                    cx = px
                    cy = py
                }
            }
            val d = sqrt(best)
            separation = d - shape.radius
            if (separation > MARGIN || d < 1e-6f) return
            outX = (cx - lx) / d
            outY = (cy - ly) / d
        }
        if (separation > MARGIN) return
        // into the world
        val c = bodies.cos[body]
        val s = bodies.sin[body]
        val wx = c * outX - s * outY
        val wy = s * outX + c * outY
        if (!inCorner(wx, wy, edges.prevNx[e], edges.prevNy[e], edges.nx[e], edges.ny[e])) return
        // the body's point is the corner pushed back out along the way to go by the separation
        val pax = qx + wx * separation
        val pay = qy + wy * separation
        add(body, -1, pax, pay, qx, qy, -wx, -wy, friction, staticFriction, restitution, shapes = si shl 16, id = ON_CORNER or (e shl 8))
    }

    /** Whether the direction ([mx], [my]) lies between the normals ([ax], [ay]) and ([bx], [by]) of a convex corner. */
    private fun inCorner(mx: Float, my: Float, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val turn = ax * by - ay * bx
        val fromA = ax * my - ay * mx
        val toB = mx * by - my * bx
        return if (turn >= 0f) fromA >= -CORNER_SLACK && toB >= -CORNER_SLACK else fromA <= CORNER_SLACK && toB <= CORNER_SLACK
    }

    /** Body [body] against a moving solid, a box, as if it stood still this substep. */
    private fun bodyAgainstSolid(body: Int, solid: Solid, index: Int) {
        val w = solid.right - solid.left
        val h = solid.bottom - solid.top
        if (w <= 0f || h <= 0f) return
        val box = solidBox(w, h)
        val cx = (solid.left + solid.right) / 2f
        val cy = (solid.top + solid.bottom) / 2f
        val m = solid.material
        val shapes = bodies.shapes[body]!!
        for (si in shapes.indices) {
            val shape = shapes[si]
            if (!narrow.collide(shape, bodies.x[body], bodies.y[body], bodies.cos[body], bodies.sin[body], box, cx, cy, 1f, 0f, manifold)) continue
            for (i in 0 until manifold.count) {
                add(body, -1, manifold.ax[i], manifold.ay[i], manifold.bx[i], manifold.by[i], manifold.normalX, manifold.normalY,
                    Material.mixFriction(bodies.friction[body], m.friction),
                    Material.mixFriction(bodies.staticFriction[body], m.staticFriction),
                    Material.mixRestitution(bodies.restitution[body], m.restitution),
                    shapes = (si shl 16) or index, id = ON_SOLID or manifold.ids[i])
            }
            if (manifold.count == 2) pair(count - 2, count - 1)
        }
    }

    // One box per size, for the moving solids, made the first time it's needed.
    private var boxWidth = 0f
    private var boxHeight = 0f
    private var box: Shape? = null

    private fun solidBox(w: Float, h: Float): Shape {
        val current = box
        if (current != null && w == boxWidth && h == boxHeight) return current
        boxWidth = w
        boxHeight = h
        return Shape.box(w, h).also { box = it }
    }

    /**
     * Adds a contact between body [p] at the world point ([ax], [ay]) and body [q] (or an edge, -1) at
     * ([bx], [by]), along the normal ([nx], [ny]) from [p] to [q], made by their [shapes] and the
     * corner and face [id] says.
     *
     * Its friction anchors are where static friction holds it: a point on each body, kept together
     * along the surface. A new contact's are its two points; the same contact found again, that held
     * on last substep, keeps its old ones, so a body resting on another stays where it first touched
     * down, not where it had crept to by the start of the substep.
     */
    private fun add(
        p: Int, q: Int, ax: Float, ay: Float, bx: Float, by: Float, nx: Float, ny: Float,
        friction: Float, staticFriction: Float, restitution: Float, shapes: Int, id: Int,
    ) {
        if (count == a.size) grow()
        val c = count++
        val bs = bodies
        a[c] = p
        b[c] = q
        r1x[c] = bs.localX(p, ax, ay)
        r1y[c] = bs.localY(p, ax, ay)
        var vx = -bs.pointVelocityX(p, ax, ay)
        var vy = -bs.pointVelocityY(p, ax, ay)
        if (q >= 0) {
            r2x[c] = bs.localX(q, bx, by)
            r2y[c] = bs.localY(q, bx, by)
            vx += bs.pointVelocityX(q, bx, by)
            vy += bs.pointVelocityY(q, bx, by)
        } else {
            r2x[c] = bx
            r2y[c] = by
        }
        shapeKey[c] = shapes
        feature[c] = id
        slipped[c] = false
        val old = recall(p, q, shapes, id)
        if (old >= 0 && oldHeld[old]) {
            f1x[c] = oldF1x[old]
            f1y[c] = oldF1y[old]
            f2x[c] = oldF2x[old]
            f2y[c] = oldF2y[old]
        } else {
            f1x[c] = r1x[c]
            f1y[c] = r1y[c]
            f2x[c] = r2x[c]
            f2y[c] = r2y[c]
        }
        this.nx[c] = nx
        this.ny[c] = ny
        lambdaN[c] = 0f
        lambdaT[c] = 0f
        twin[c] = -1
        grip[c] = friction
        hold[c] = staticFriction
        bounce[c] = restitution
        speedIn[c] = vx * nx + vy * ny
    }

    /** Lets every edge contact of the last substep be found as new: the edges were numbered afresh. */
    private fun forgetEdges() {
        for (k in 0 until oldCount) if (oldB[k] < 0 && oldFeature[k] and KIND != ON_SOLID) oldHeld[k] = false
    }


    /** Makes contacts [c] and [d], two points on the same two bodies along the same normal, be solved together. */
    private fun pair(c: Int, d: Int) {
        twin[c] = d
        twin[d] = c
    }

    /**
     * One pass over every contact: non-penetration, then static friction. The two points of a face
     * lying on a face are pushed apart together, so neither goes first: one at a time, the first would
     * tip the body, the second only nearly tip it back, and a stack would lean a little more every pass.
     */
    internal fun solve() {
        for (c in 0 until count) {
            val t = twin[c]
            if (t in 0 until c) continue
            // The push, then the grip, so a pass ends with nothing sliding: a push left a little out
            // is measured again next pass, from where the surfaces are, but grip only knows how far
            // the points have slid since the substep began, and a slide left over stays for good.
            // Once a contact has pushed this substep, it holds on for the rest of it, even where
            // another contact's push has just lifted it clear: that push turned the body, and slid
            // this point.
            if (t > c) {
                solvePair(c, t)
                if (lambdaN[c] > 0f || lambdaN[t] > 0f) staticFrictionPair(c, t)
            } else {
                solveOne(c)
                if (lambdaN[c] > 0f) staticFriction(c)
            }
        }
    }



    // What [measure] found: each point from its body's centre, and how far apart they are along the normal.
    private var mw1x = 0f
    private var mw1y = 0f
    private var mw2x = 0f
    private var mw2y = 0f
    private var mGap = 0f

    /** Measures contact [c] where its bodies are now, into the `m` fields: negative [mGap] is overlap. */
    private fun measure(c: Int) {
        val bs = bodies
        val p = a[c]
        val q = b[c]
        mw1x = bs.cos[p] * r1x[c] - bs.sin[p] * r1y[c]
        mw1y = bs.sin[p] * r1x[c] + bs.cos[p] * r1y[c]
        val p1x = bs.x[p] + mw1x
        val p1y = bs.y[p] + mw1y
        val p2x: Float
        val p2y: Float
        if (q >= 0) {
            mw2x = bs.cos[q] * r2x[c] - bs.sin[q] * r2y[c]
            mw2y = bs.sin[q] * r2x[c] + bs.cos[q] * r2y[c]
            p2x = bs.x[q] + mw2x
            p2y = bs.y[q] + mw2y
        } else {
            mw2x = 0f
            mw2y = 0f
            p2x = r2x[c]
            p2y = r2y[c]
        }
        // how far B's point is past A's along the normal
        mGap = (p2x - p1x) * nx[c] + (p2y - p1y) * ny[c]
    }

    /** Pushes contact [c]'s two points apart along its normal, if they overlap, until they don't. */
    private fun solveOne(c: Int) {
        measure(c)
        if (mGap >= 0f) return
        val p = a[c]
        val q = b[c]
        val w = bodies.inverseMassAt(p, mw1x, mw1y, nx[c], ny[c]) + (if (q >= 0) bodies.inverseMassAt(q, mw2x, mw2y, nx[c], ny[c]) else 0f)
        if (w == 0f) return
        push(c, -mGap / w, mw1x, mw1y, mw2x, mw2y)
    }

    /**
     * Pushes the two points of contacts [c] and [d] apart at once: the pushes, neither pulling, that
     * leave both just touching, or one just touching and the other clear.
     */
    private fun solvePair(c: Int, d: Int) {
        measure(c)
        val g1 = mGap
        val c1x = mw1x
        val c1y = mw1y
        val c2x = mw2x
        val c2y = mw2y
        measure(d)
        val g2 = mGap
        if (g1 >= 0f && g2 >= 0f) return
        pairResponse(c, c1x, c1y, c2x, c2y, mw1x, mw1y, mw2x, mw2y)
        if (!pairPushes(g1, g2, allowPull = false)) {
            // the two points are as good as one: one at a time does no harm
            solveOne(c)
            solveOne(d)
            return
        }
        if (l1 > 0f) push(c, l1, c1x, c1y, c2x, c2y)
        if (l2 > 0f) push(d, l2, mw1x, mw1y, mw2x, mw2y)
    }

    // How far a push at each of a pair's points moves each apart: see [pairResponse].
    private var k11 = 0f
    private var k22 = 0f
    private var k12 = 0f

    // The pushes [pairPushes] found.
    private var l1 = 0f
    private var l2 = 0f

    /**
     * Works out, for contacts [c] and [d] of the same two bodies along the same normal, at points
     * ([c1x], [c1y]) and ([d1x], [d1y]) from A's centre and ([c2x], [c2y]) and ([d2x], [d2y]) from B's,
     * how far a unit push at each moves each point apart, through each body's mass and turning: the
     * `k` fields. The same numbers say how fast a unit impulse makes them part.
     */
    private fun pairResponse(c: Int, c1x: Float, c1y: Float, c2x: Float, c2y: Float, d1x: Float, d1y: Float, d2x: Float, d2y: Float) {
        val bs = bodies
        val p = a[c]
        val q = b[c]
        val n1 = nx[c]
        val n2 = ny[c]
        val ac = c1x * n2 - c1y * n1
        val ad = d1x * n2 - d1y * n1
        k11 = bs.invMass[p] + ac * ac * bs.invInertia[p]
        k22 = bs.invMass[p] + ad * ad * bs.invInertia[p]
        k12 = bs.invMass[p] + ac * ad * bs.invInertia[p]
        if (q >= 0) {
            val bc = c2x * n2 - c2y * n1
            val bd = d2x * n2 - d2y * n1
            k11 += bs.invMass[q] + bc * bc * bs.invInertia[q]
            k22 += bs.invMass[q] + bd * bd * bs.invInertia[q]
            k12 += bs.invMass[q] + bc * bd * bs.invInertia[q]
        }
    }

    /**
     * The two pushes, into [l1] and [l2], that bring a pair [g1] and [g2] short of where it should be
     * up to it, with the `k` fields from [pairResponse]. Unless [allowPull], neither may pull, and a
     * point already past where it should be is left there: both pushing and both just there, or one
     * pushing and the other clear. False when the two points are as good as one, and can't be told apart.
     */
    private fun pairPushes(g1: Float, g2: Float, allowPull: Boolean): Boolean {
        l1 = 0f
        l2 = 0f
        if (k11 <= 0f || k22 <= 0f) return true
        val det = k11 * k22 - k12 * k12
        if (det <= PAIR_CONDITION * k11 * k22) return false
        val both1 = (-g1 * k22 + g2 * k12) / det
        val both2 = (-g2 * k11 + g1 * k12) / det
        if (allowPull || both1 >= 0f && both2 >= 0f) {
            l1 = both1
            l2 = both2
            return true
        }
        val only1 = -g1 / k11
        if (only1 >= 0f && g2 + k12 * only1 >= 0f) {
            l1 = only1
            return true
        }
        val only2 = -g2 / k22
        if (only2 >= 0f && g1 + k12 * only2 >= 0f) l2 = only2
        return true
    }

    /** Pushes contact [c]'s bodies apart by [amount] along its normal, at its points [w1x], [w1y] and [w2x], [w2y] from their centres. */
    private fun push(c: Int, amount: Float, w1x: Float, w1y: Float, w2x: Float, w2y: Float) {
        lambdaN[c] += amount
        val n1 = nx[c]
        val n2 = ny[c]
        bodies.shove(a[c], -amount * n1, -amount * n2, w1x, w1y)
        if (b[c] >= 0) bodies.shove(b[c], amount * n1, amount * n2, w2x, w2y)
    }

    // What [slide] found: each point from its body's centre, and how far B's point has slid past A's along the surface.
    private var sw1x = 0f
    private var sw1y = 0f
    private var sw2x = 0f
    private var sw2y = 0f
    private var slideX = 0f
    private var slideY = 0f

    /**
     * Measures how far contact [c]'s friction anchor on A is from its anchor on B along the surface,
     * into the `s` fields, with the points it's held at, from each body's centre.
     */
    private fun slide(c: Int) {
        val bs = bodies
        val p = a[c]
        val q = b[c]
        sw1x = bs.cos[p] * f1x[c] - bs.sin[p] * f1y[c]
        sw1y = bs.sin[p] * f1x[c] + bs.cos[p] * f1y[c]
        var dx = bs.x[p] + sw1x
        var dy = bs.y[p] + sw1y
        if (q >= 0) {
            sw2x = bs.cos[q] * f2x[c] - bs.sin[q] * f2y[c]
            sw2y = bs.sin[q] * f2x[c] + bs.cos[q] * f2y[c]
            dx -= bs.x[q] + sw2x
            dy -= bs.y[q] + sw2y
        } else {
            sw2x = 0f
            sw2y = 0f
            dx -= f2x[c]
            dy -= f2y[c]
        }
        val along = dx * nx[c] + dy * ny[c]
        slideX = dx - along * nx[c]
        slideY = dy - along * ny[c]
    }


    /**
     * Holds contact [c]'s two points together along the surface: undoes how far they've slid past each
     * other this substep, while the push that takes is under its static friction times the push holding
     * them on.
     */
    private fun staticFriction(c: Int) {
        slide(c)
        hold(c, c, lambdaN[c], slideX, slideY, sw1x, sw1y, sw2x, sw2y)
    }

    /**
     * Static friction for the two points of a face on a face: each point's slide undone, while all the
     * push along the surface the two take is under the static friction times both their pushes holding
     * them on. Held at both, the face can neither slide nor tip; and one corner holding hardly at all,
     * where the other carries the weight, still holds its share.
     */
    private fun staticFrictionPair(c: Int, d: Int) {
        val pushed = lambdaN[c] + lambdaN[d]
        slide(c)
        hold(c, c, pushed, slideX, slideY, sw1x, sw1y, sw2x, sw2y)
        slide(d)
        hold(d, c, pushed, slideX, slideY, sw1x, sw1y, sw2x, sw2y)
        slipped[d] = slipped[c]
    }


    /**
     * Undoes the slide ([dx], [dy]) between the points ([w1x], [w1y]) and ([w2x], [w2y]) from the
     * centres of contact [c]'s bodies, if the push along the surface that takes, with what contact
     * [kept] has pushed that way so far this substep, is under its static friction times [pushed], the
     * push holding it on.
     */
    private fun hold(c: Int, kept: Int, pushed: Float, dx: Float, dy: Float, w1x: Float, w1y: Float, w2x: Float, w2y: Float) {
        val slide = sqrt(dx * dx + dy * dy)
        if (slide < 1e-6f) return
        val ux = dx / slide
        val uy = dy / slide
        val bs = bodies
        val p = a[c]
        val q = b[c]
        val w = bs.inverseMassAt(p, w1x, w1y, ux, uy) + (if (q >= 0) bs.inverseMassAt(q, w2x, w2y, ux, uy) else 0f)
        if (w == 0f) return
        val amount = slide / w
        if (lambdaT[kept] + amount >= hold[c] * pushed) {
            slipped[kept] = true
            return
        }
        lambdaT[kept] += amount
        bs.shove(p, -amount * ux, -amount * uy, w1x, w1y)
        if (q >= 0) bs.shove(q, amount * ux, amount * uy, w2x, w2y)
    }


    /**
     * After a substep of [h] seconds, for every contact that pushed: sets how fast its points part
     * along the normal, then takes off sliding speed for the dynamic friction, by an impulse up to its
     * friction times the push's. Setting one point's speed turns the body, which changes the others',
     * so that's done [passes] times over; friction goes last, as the turning changes the sliding too.
     *
     * - A hit, a contact that came in faster than [bounceSpeed], leaves at the speed it came in at
     *   times its restitution, exactly: a box landing hard sinks in by a whole substep's move, and the
     *   push back out would throw it off again.
     * - The two points of a face on a face both leave at the speed they should, exactly, together, so
     *   the face stays flat on the face.
     * - Any other point is only stopped closing in, never pulled back. Pulled back one at a time, as
     *   Müller does, the corners down a tall stack never quite agree, and the stack leans a little more
     *   every substep until it falls.
     */
    internal fun solveVelocities(h: Float, bounceSpeed: Float, passes: Int) {
        for (c in 0 until count) slip[c] = 0f
        repeat(passes) {
            for (c in 0 until count) {
                val t = twin[c]
                if (t in 0 until c) continue
                if (t > c) {
                    if (lambdaN[c] == 0f && lambdaN[t] == 0f) continue
                    if (lambdaN[c] > 0f && lambdaN[t] > 0f) bouncePair(c, t, bounceSpeed) else bounce(if (lambdaN[c] > 0f) c else t, bounceSpeed)
                    dynamicFrictionPair(c, t, h)
                } else {
                    if (lambdaN[c] == 0f) continue
                    bounce(c, bounceSpeed)
                    dynamicFriction(c, h)
                }
            }
        }
    }


    // What [closing] found: each point from its body's centre, and how fast B's point leaves A's along the normal.
    private var vw1x = 0f
    private var vw1y = 0f
    private var vw2x = 0f
    private var vw2y = 0f
    private var vLeaving = 0f

    /** Measures how fast contact [c]'s points part along its normal now, into the `v` fields. */
    private fun leaving(c: Int) {
        val bs = bodies
        val p = a[c]
        val q = b[c]
        vw1x = bs.cos[p] * r1x[c] - bs.sin[p] * r1y[c]
        vw1y = bs.sin[p] * r1x[c] + bs.cos[p] * r1y[c]
        vw2x = if (q >= 0) bs.cos[q] * r2x[c] - bs.sin[q] * r2y[c] else 0f
        vw2y = if (q >= 0) bs.sin[q] * r2x[c] + bs.cos[q] * r2y[c] else 0f
        vLeaving = relativeVelocityX(p, q, vw1y, vw2y) * nx[c] + relativeVelocityY(p, q, vw1x, vw2x) * ny[c]
    }

    /** Whether contact [c] hit, faster than [bounceSpeed], and how fast it should leave: the speed it came in at times its bounce. */
    private fun isHit(c: Int, bounceSpeed: Float): Boolean = -speedIn[c] > bounceSpeed

    private fun leaveAt(c: Int, bounceSpeed: Float): Float = if (isHit(c, bounceSpeed)) -bounce[c] * speedIn[c] else 0f

    /** Sets how fast contact [c]'s points part: exactly, for a hit, and else at least not closing in. */
    private fun bounce(c: Int, bounceSpeed: Float) {
        leaving(c)
        val change = leaveAt(c, bounceSpeed) - vLeaving
        if (change < 1e-6f && !(isHit(c, bounceSpeed) && change < -1e-6f)) return
        val p = a[c]
        val q = b[c]
        val w = bodies.inverseMassAt(p, vw1x, vw1y, nx[c], ny[c]) + (if (q >= 0) bodies.inverseMassAt(q, vw2x, vw2y, nx[c], ny[c]) else 0f)
        if (w == 0f) return
        kick(c, change / w, vw1x, vw1y, vw2x, vw2y)
    }

    /**
     * [bounce] for the two points of a face on a face, together, and exactly, pulled back if need be:
     * both corners leave at the speed they should, so the face stays flat on the face. Only pushed, a
     * box gripping a slope tips on its downhill corner, the uphill one lifts, and with only one corner
     * holding on, it slips.
     */
    private fun bouncePair(c: Int, d: Int, bounceSpeed: Float) {
        leaving(c)
        val g1 = vLeaving - leaveAt(c, bounceSpeed)
        val c1x = vw1x
        val c1y = vw1y
        val c2x = vw2x
        val c2y = vw2y
        leaving(d)
        val g2 = vLeaving - leaveAt(d, bounceSpeed)
        pairResponse(c, c1x, c1y, c2x, c2y, vw1x, vw1y, vw2x, vw2y)
        if (!pairPushes(g1, g2, allowPull = true)) {
            bounce(c, bounceSpeed)
            bounce(d, bounceSpeed)
            return
        }
        kick(c, l1, c1x, c1y, c2x, c2y)
        kick(d, l2, vw1x, vw1y, vw2x, vw2y)
    }

    /** Strikes contact [c]'s bodies apart with the impulse [amount] along its normal, at its points [w1x], [w1y] and [w2x], [w2y] from their centres. */
    private fun kick(c: Int, amount: Float, w1x: Float, w1y: Float, w2x: Float, w2y: Float) {
        bodies.kick(a[c], -amount * nx[c], -amount * ny[c], w1x, w1y)
        if (b[c] >= 0) bodies.kick(b[c], amount * nx[c], amount * ny[c], w2x, w2y)
    }

    /**
     * Cuts how fast contact [c]'s two points slide past each other: strikes them along the surface,
     * so that, with what it has struck them already this substep, the friction is no more than its
     * friction times the impulse its push gave over [h], either way.
     */
    private fun dynamicFriction(c: Int, h: Float) {
        leaving(c)
        strike(c, grip[c] * lambdaN[c] / h, vw1x, vw1y, vw2x, vw2y)
    }

    /** [dynamicFriction] for the two points of a face on a face: one strike, between them, up to what both their pushes allow. */
    private fun dynamicFrictionPair(c: Int, d: Int, h: Float) {
        leaving(c)
        val c1x = vw1x
        val c1y = vw1y
        val c2x = vw2x
        val c2y = vw2y
        leaving(d)
        strike(c, grip[c] * (lambdaN[c] + lambdaN[d]) / h, (c1x + vw1x) / 2f, (c1y + vw1y) / 2f, (c2x + vw2x) / 2f, (c2y + vw2y) / 2f)
    }

    /**
     * Strikes contact [c]'s bodies along its surface, at their points [w1x], [w1y] and [w2x], [w2y]
     * from their centres, to stop the points sliding past each other there, keeping what [slip] adds
     * up to within [most] either way.
     */
    private fun strike(c: Int, most: Float, w1x: Float, w1y: Float, w2x: Float, w2y: Float) {
        val bs = bodies
        val p = a[c]
        val q = b[c]
        // along the surface, the same way every pass, so the strikes add up
        val tx = -ny[c]
        val ty = nx[c]
        val sliding = relativeVelocityX(p, q, w1y, w2y) * tx + relativeVelocityY(p, q, w1x, w2x) * ty
        val w = bs.inverseMassAt(p, w1x, w1y, tx, ty) + (if (q >= 0) bs.inverseMassAt(q, w2x, w2y, tx, ty) else 0f)
        if (w == 0f) return
        val before = slip[c]
        val after = (before + sliding / w).coerceIn(-most, most)
        val impulse = after - before
        if (impulse == 0f) return
        slip[c] = after
        bs.kick(p, impulse * tx, impulse * ty, w1x, w1y)
        if (q >= 0) bs.kick(q, -impulse * tx, -impulse * ty, w2x, w2y)
    }


    /** How fast B's point, [w2x], [w2y] from its centre, moves past A's, [w1x], [w1y] from its, along x; B is still for an edge. */
    private fun relativeVelocityX(p: Int, q: Int, w1y: Float, w2y: Float): Float {
        val bs = bodies
        val own = -(bs.vx[p] - bs.omega[p] * w1y)
        return if (q >= 0) own + bs.vx[q] - bs.omega[q] * w2y else own
    }

    private fun relativeVelocityY(p: Int, q: Int, w1x: Float, w2x: Float): Float {
        val bs = bodies
        val own = -(bs.vy[p] + bs.omega[p] * w1x)
        return if (q >= 0) own + bs.vy[q] + bs.omega[q] * w2x else own
    }

    private fun grow() {
        val size = a.size * 2
        a = a.copyOf(size)
        b = b.copyOf(size)
        r1x = r1x.copyOf(size)
        r1y = r1y.copyOf(size)
        r2x = r2x.copyOf(size)
        r2y = r2y.copyOf(size)
        nx = nx.copyOf(size)
        ny = ny.copyOf(size)
        lambdaN = lambdaN.copyOf(size)
        lambdaT = lambdaT.copyOf(size)
        grip = grip.copyOf(size)
        hold = hold.copyOf(size)
        bounce = bounce.copyOf(size)
        speedIn = speedIn.copyOf(size)
        f1x = f1x.copyOf(size)
        f1y = f1y.copyOf(size)
        f2x = f2x.copyOf(size)
        f2y = f2y.copyOf(size)
        shapeKey = shapeKey.copyOf(size)
        feature = feature.copyOf(size)
        slipped = slipped.copyOf(size)
        twin = twin.copyOf(size)
        slip = slip.copyOf(size)
    }

    companion object {
        /** Shapes nearer than this make a contact already, so one that closes in during the substep is caught. */
        const val MARGIN = 0.5f

        /** A point further behind an edge than this is through the solid, not touching it: the far side's business. */
        const val MAX_DEPTH = 8f

        /** What a contact's feature id says it touched besides another body: an edge of the solids, a corner of them, or a moving solid. */
        private const val KIND = 3 shl 29
        private const val ON_EDGE = 1 shl 29
        private const val ON_CORNER = 2 shl 29
        private const val ON_SOLID = 3 shl 29

        /** Two points closer to being one than this, by how alike their pushes are, are solved one at a time. */
        const val PAIR_CONDITION = 1e-3f

        /** How far outside a corner's span a push may lean and still count, for corners met edge on. */
        const val CORNER_SLACK = 0.02f
    }
}
