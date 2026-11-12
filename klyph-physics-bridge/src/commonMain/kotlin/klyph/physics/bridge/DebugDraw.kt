package klyph.physics.bridge

import klyph.math.Rect
import klyph.physics.Broadphase
import klyph.physics.Constraints
import klyph.physics.Manifold
import klyph.physics.Outline
import klyph.physics.Particles
import klyph.physics.PhysicsWorld
import klyph.physics.RigidBodies
import klyph.physics.Ring
import klyph.physics.Shape
import klyph.physics.ShapeKind
import klyph.physics.Solid
import klyph.physics.SolidGrid
import klyph.physics.SolidShape
import kotlin.math.PI
import kotlin.math.abs
import klyph.platform.Color
import klyph.render.Canvas
import klyph.render.DrawSpace
import klyph.render.TextureRegion
import klyph.text.Font
import klyph.text.TextAlign
import klyph.text.TextShadow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws what the physics sees, in world space, on a klyph [Canvas]: soft bodies filled from their
 * outlines, and the solids they collide with. Its [view] adds more, a level at a time: every particle
 * and tile edge, then every constraint coloured by its stress, then every contact and its normal (see
 * [DebugView]). [showBroadphase] adds the broadphase's cells on top. What isn't shown costs nothing.
 *
 * klyph has no polygons, so shapes are filled one row of [ROW] world units at a time, and lines are
 * stretched, turned quads of [pixel] (`App.assets.whitePixel`). Labels need a [font], such as
 * `App.assets.defaultFont`; without one they are not drawn.
 */
class DebugDraw(private val pixel: TextureRegion, private val font: Font? = null) {
    /** How much to draw on top of the plain view. */
    var view: DebugView = DebugView.OFF

    /** Whether particles and tile edges are drawn: any [view] but [DebugView.OFF]. */
    val showDetails: Boolean get() = view.shows(DebugView.BODIES)

    /** Whether [overlay] also shades the broadphase's cells and outlines its boxes. */
    var showBroadphase: Boolean = false

    /** The canvas layer the plain view is drawn in; details go in the layers just above it. */
    var layer: Int = 0

    private var spanLeft = FloatArray(32)
    private var spanRight = FloatArray(32)

    /**
     * Draws every live particle in [particles] as a square as wide as the particle (at least [DOT]), in the
     * colour [colorOf] picks for its handle.
     */
    inline fun particles(canvas: Canvas, particles: Particles, colorOf: (Int) -> Color) {
        for (p in 0 until particles.end) {
            if (!particles.isAlive(p)) continue
            val size = max(particles.radius(p) * 2f, DOT)
            square(canvas, particles.x(p), particles.y(p), size, colorOf(p))
        }
    }

    /** Draws every live distance and rope in [constraints] as a line between its particles: ropes in [ropeColor]. */
    fun links(canvas: Canvas, constraints: Constraints, particles: Particles, color: Color = LINK, ropeColor: Color = ROPE) {
        for (c in 0 until constraints.linkEnd) {
            if (!constraints.isLinkAlive(c)) continue
            val a = constraints.linkA(c)
            val b = constraints.linkB(c)
            if (!particles.isAlive(a) || !particles.isAlive(b)) continue
            val tint = if (constraints.isRope(c)) ropeColor else color
            line(canvas, particles.x(a), particles.y(a), particles.x(b), particles.y(b), OUTLINE, tint, layer)
        }
    }

    /**
     * Fills a rectangle [width] by [height] centred on ([x], [y]) and turned by [angle] radians, in
     * [color], with a darker edge: how a rigid box looks.
     */
    fun turnedRect(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, angle: Float, color: Color) {
        canvas.drawSprite(pixel, x = x, y = y, width = width, height = height, rotation = angle, color = color, layer = layer, space = DrawSpace.WORLD)
        val c = cos(angle)
        val s = sin(angle)
        val hx = width / 2f
        val hy = height / 2f
        val edge = color.darker()
        for (k in 0 until 4) {
            // the corners in turn, round the rectangle
            val ax = if (k == 0 || k == 3) -hx else hx
            val ay = if (k < 2) -hy else hy
            val bx = if (k == 0 || k == 1) hx else -hx
            val by = if (k == 0 || k == 3) -hy else hy
            line(canvas, x + c * ax - s * ay, y + s * ax + c * ay, x + c * bx - s * by, y + s * bx + c * by, OUTLINE, edge, layer + 1)
        }
    }

    /**
     * Fills every shape of rigid body [body] of [world] in [color] and outlines it, with a spoke on each
     * round shape so its turning shows; with details, also marks its centre of mass.
     */
    fun rigidBody(canvas: Canvas, world: PhysicsWorld, body: Int, color: Color) {
        val bodies = world.rigidBodies
        val edge = color.darker()
        for (i in 0 until bodies.shapeCount(body)) {
            val shape = bodies.shape(body, i)
            val n = outline(bodies, body, shape)
            fillConvex(canvas, n, color)
            for (k in 0 until n) {
                val j = if (k + 1 == n) 0 else k + 1
                line(canvas, outlineX[k], outlineY[k], outlineX[j], outlineY[j], OUTLINE, edge, layer + 1)
            }
            if (shape.kind == ShapeKind.CIRCLE) {
                val cx = bodies.worldX(body, shape.vertexX(0), shape.vertexY(0))
                val cy = bodies.worldY(body, shape.vertexX(0), shape.vertexY(0))
                // along the body's own x axis, so it turns with it
                val tipX = bodies.worldX(body, shape.vertexX(0) + shape.radius, shape.vertexY(0))
                val tipY = bodies.worldY(body, shape.vertexX(0) + shape.radius, shape.vertexY(0))
                line(canvas, cx, cy, tipX, tipY, OUTLINE, edge, layer + 1)
            }
        }
        if (showDetails) rect(canvas, bodies.x(body) - CORE_DOT / 2f, bodies.y(body) - CORE_DOT / 2f, CORE_DOT, CORE_DOT, CORE, layer + 3)
    }

    /**
     * Draws where two shapes touch, as [manifold] found it: each point as a dot on each shape's
     * surface, joined, and a line from the second shape's along the normal, in [color].
     */
    fun manifold(canvas: Canvas, manifold: Manifold, color: Color = CONTACT) {
        val top = layer + 7
        for (i in 0 until manifold.count) {
            val ax = manifold.pointAX(i)
            val ay = manifold.pointAY(i)
            val bx = manifold.pointBX(i)
            val by = manifold.pointBY(i)
            line(canvas, ax, ay, bx, by, EDGE, color, top)
            line(canvas, bx, by, bx + manifold.normalX * NORMAL, by + manifold.normalY * NORMAL, EDGE, color, top)
            rect(canvas, ax - 1f, ay - 1f, 2f, 2f, color, top)
            rect(canvas, bx - 1f, by - 1f, 2f, 2f, color, top)
        }
    }

    private val outlineX = FloatArray(MAX_OUTLINE)
    private val outlineY = FloatArray(MAX_OUTLINE)

    /**
     * Writes the outline of [shape], a shape of [body], in the world into [outlineX] and [outlineY], and
     * returns how many points it has: a polygon's corners, or round ends made of short straight edges.
     */
    private fun outline(bodies: RigidBodies, body: Int, shape: Shape): Int {
        val angle = bodies.angle(body)
        return when (shape.kind) {
            ShapeKind.POLYGON -> {
                for (k in 0 until shape.vertexCount) {
                    outlineX[k] = bodies.worldX(body, shape.vertexX(k), shape.vertexY(k))
                    outlineY[k] = bodies.worldY(body, shape.vertexX(k), shape.vertexY(k))
                }
                shape.vertexCount
            }
            ShapeKind.CIRCLE -> {
                val cx = bodies.worldX(body, shape.vertexX(0), shape.vertexY(0))
                val cy = bodies.worldY(body, shape.vertexX(0), shape.vertexY(0))
                arc(0, cx, cy, shape.radius, angle, 2f * PI_F, ROUND_STEPS)
            }
            ShapeKind.CAPSULE -> {
                val ax = bodies.worldX(body, shape.vertexX(0), shape.vertexY(0))
                val ay = bodies.worldY(body, shape.vertexX(0), shape.vertexY(0))
                val bx = bodies.worldX(body, shape.vertexX(1), shape.vertexY(1))
                val by = bodies.worldY(body, shape.vertexX(1), shape.vertexY(1))
                // the segment's direction, then a half circle round each end
                val along = atan2(by - ay, bx - ax)
                val half = ROUND_STEPS / 2
                val n = arc(0, bx, by, shape.radius, along - PI_F / 2f, PI_F, half)
                arc(n, ax, ay, shape.radius, along + PI_F / 2f, PI_F, half)
            }
        }
    }

    /** Adds [steps] + 1 points from [from] round an arc of [sweep] radians about ([cx], [cy]); returns the new count. */
    private fun arc(start: Int, cx: Float, cy: Float, r: Float, from: Float, sweep: Float, steps: Int): Int {
        val full = sweep >= 2f * PI_F
        val count = if (full) steps else steps + 1
        for (k in 0 until count) {
            val a = from + sweep * k / steps
            outlineX[start + k] = cx + r * cos(a)
            outlineY[start + k] = cy + r * sin(a)
        }
        return start + count
    }

    /** Fills the convex outline of [n] points in [outlineX] and [outlineY] row by row in [color]. */
    private fun fillConvex(canvas: Canvas, n: Int, color: Color) {
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (k in 0 until n) {
            top = min(top, outlineY[k])
            bottom = max(bottom, outlineY[k])
        }
        var y = floor(top / ROW) * ROW
        while (y < bottom) {
            val mid = y + ROW / 2f
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (k in 0 until n) {
                val j = if (k + 1 == n) 0 else k + 1
                val y0 = outlineY[k]
                val y1 = outlineY[j]
                if ((y0 <= mid) == (y1 <= mid)) continue
                val x = outlineX[k] + (mid - y0) / (y1 - y0) * (outlineX[j] - outlineX[k])
                left = min(left, x)
                right = max(right, x)
            }
            if (left < right) rect(canvas, left, y, right - left, ROW, color, layer)
            y += ROW
        }
    }

    /** Draws a square [size] wide centred on ([x], [y]) in [color], in the plain view's layer. */
    fun square(canvas: Canvas, x: Float, y: Float, size: Float, color: Color) {
        rect(canvas, x - size / 2f, y - size / 2f, size, size, color, layer)
    }

    /** Fills soft body [body] of [world] in [color] and outlines it; with details, also marks every particle. */
    fun softBody(canvas: Canvas, world: PhysicsWorld, body: Int, color: Color) {
        val bodies = world.softBodies
        ring(canvas, bodies.ring(body), bodies.minY(body), bodies.maxY(body), color)
        if (!showDetails) return
        val ps = world.particles
        for (i in 0..bodies.ringCount(body)) {
            val p = bodies.particle(body, i)
            val isCore = i == bodies.ringCount(body)
            val dot = if (isCore) CORE_DOT else DOT
            rect(canvas, ps.x(p) - dot / 2f, ps.y(p) - dot / 2f, dot, dot, if (isCore) CORE else PARTICLE, layer + 3)
        }
    }

    /** Fills the outline [ring], which spans [top] to [bottom], row by row in [color], and draws its edge. */
    private fun ring(canvas: Canvas, ring: Ring, top: Float, bottom: Float, color: Color) {
        if (spanLeft.size < ring.ringCount) {
            spanLeft = FloatArray(ring.ringCount)
            spanRight = FloatArray(ring.ringCount)
        }
        var y = floor(top / ROW) * ROW
        while (y < bottom) {
            val count = Outline.spans(ring, y + ROW / 2f, spanLeft, spanRight)
            for (k in 0 until count) rect(canvas, spanLeft[k], y, spanRight[k] - spanLeft[k], ROW, color, layer)
            y += ROW
        }
        val edge = color.darker()
        val n = ring.ringCount
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            line(canvas, ring.ringX(i), ring.ringY(i), ring.ringX(j), ring.ringY(j), OUTLINE, edge, layer + 1)
        }
    }

    /**
     * Everything [view] and [showBroadphase] ask for that a scene doesn't draw itself: [constraints],
     * [contacts] and [broadphase]. Call it after the scene, every frame.
     */
    fun overlay(canvas: Canvas, world: PhysicsWorld) {
        constraints(canvas, world)
        contacts(canvas, world)
        if (showBroadphase) broadphase(canvas, world)
    }

    /**
     * From [DebugView.CONSTRAINTS] up, draws every constraint of [world] coloured by its stress, from
     * blue squeezed through grey at rest to red stretched: a link along its length, a pin as a line to
     * the point it holds, a bend as a dot on its middle particle, an area as a dot in its middle.
     */
    fun constraints(canvas: Canvas, world: PhysicsWorld) {
        if (!view.shows(DebugView.CONSTRAINTS)) return
        val ps = world.particles
        val cs = world.constraints
        val top = layer + 6
        for (c in 0 until cs.linkEnd) {
            if (!cs.isLinkAlive(c)) continue
            val a = cs.linkA(c)
            val b = cs.linkB(c)
            if (!ps.isAlive(a) || !ps.isAlive(b)) continue
            val strain = cs.strain(c)
            val tint = if (cs.isRope(c) && strain < 0f) SLACK else stressColor(strain / FULL_STRAIN)
            line(canvas, ps.x(a), ps.y(a), ps.x(b), ps.y(b), EDGE, tint, top)
        }
        for (c in 0 until cs.pinEnd) {
            if (!cs.isPinAlive(c)) continue
            val p = cs.pinnedParticle(c)
            if (!ps.isAlive(p)) continue
            val dx = cs.pinX(c) - ps.x(p)
            val dy = cs.pinY(c) - ps.y(p)
            val tint = stressColor(sqrt(dx * dx + dy * dy) / FULL_PIN)
            line(canvas, ps.x(p), ps.y(p), cs.pinX(c), cs.pinY(c), EDGE, tint, top)
            rect(canvas, cs.pinX(c) - 1f, cs.pinY(c) - 1f, 2f, 2f, tint, top)
        }
        for (c in 0 until cs.bendEnd) {
            if (!cs.isBendAlive(c)) continue
            val p = cs.bendB(c)
            if (!ps.isAlive(p)) continue
            var off = cs.bendAngle(c) - cs.restAngle(c)
            if (off > PI_F) off -= 2f * PI_F
            if (off < -PI_F) off += 2f * PI_F
            rect(canvas, ps.x(p) - 1f, ps.y(p) - 1f, 2f, 2f, stressColor(abs(off) / FULL_BEND), top + 1)
        }
        for (c in 0 until cs.areaEnd) {
            if (!cs.isAreaAlive(c)) continue
            val n = cs.ringSize(c)
            var x = 0f
            var y = 0f
            for (i in 0 until n) {
                x += ps.x(cs.ringParticle(c, i))
                y += ps.y(cs.ringParticle(c, i))
            }
            val change = cs.currentArea(c) / cs.restArea(c) - 1f
            rect(canvas, x / n - 2f, y / n - 2f, 4f, 4f, stressColor(change / FULL_AREA), top + 1)
        }
    }

    /**
     * From [DebugView.EVERYTHING] up, marks every particle of [world] that touched something during the
     * last step: a dot where it touched, and a short line along the normal it was pushed out by, in
     * yellow on a floor and cyan on anything else.
     */
    fun contacts(canvas: Canvas, world: PhysicsWorld) {
        if (!view.shows(DebugView.EVERYTHING)) return
        val ps = world.particles
        val events = world.events
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p) || !events.isTouching(p)) continue
            val nx = events.contactNormalX(p)
            val ny = events.contactNormalY(p)
            val r = ps.radius(p)
            val x = ps.x(p) - nx * r
            val y = ps.y(p) - ny * r
            val tint = if (events.isOnFloor(p)) FLOOR_CONTACT else CONTACT
            line(canvas, x, y, x + nx * NORMAL, y + ny * NORMAL, EDGE, tint, layer + 7)
            rect(canvas, x - 0.75f, y - 0.75f, 1.5f, 1.5f, tint, layer + 7)
        }
    }

    /**
     * Shades every cell of [world]'s broadphase once per box filed there, so busy cells glow brighter,
     * and outlines each box: one per soft body and one per colliding particle.
     */
    fun broadphase(canvas: Canvas, world: PhysicsWorld) {
        val bp = world.broadphase
        val size = bp.cellSize
        for (e in 0 until bp.count) {
            val col0 = floor(bp.left(e) / size).toInt()
            val col1 = floor(bp.right(e) / size).toInt()
            val row0 = floor(bp.top(e) / size).toInt()
            val row1 = floor(bp.bottom(e) / size).toInt()
            for (row in row0..row1) for (col in col0..col1) rect(canvas, col * size, row * size, size, size, CELL, layer - 1)
            box(canvas, bp.left(e), bp.top(e), bp.right(e), bp.bottom(e), BOX, layer + 6)
        }
    }

    private fun box(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Color, layer: Int) {
        line(canvas, left, top, right, top, EDGE, color, layer)
        line(canvas, right, top, right, bottom, EDGE, color, layer)
        line(canvas, right, bottom, left, bottom, EDGE, color, layer)
        line(canvas, left, bottom, left, top, EDGE, color, layer)
    }

    /**
     * Draws the solids of [grid] that lie in [view], tiles and moving ones alike, in [color]. Tiles
     * outside [view] cost nothing; with details, each tile's edge is drawn too.
     */
    fun solids(canvas: Canvas, grid: SolidGrid, view: Rect, color: Color = SOLID) {
        val t = grid.tileSize
        val firstCol = max(0, floor(view.x / t).toInt())
        val lastCol = min(grid.cols - 1, floor((view.x + view.width) / t).toInt())
        val firstRow = max(0, floor(view.y / t).toInt())
        val lastRow = min(grid.rows - 1, floor((view.y + view.height) / t).toInt())
        for (row in firstRow..lastRow) for (col in firstCol..lastCol) {
            val solid = grid.at(col, row) ?: continue
            solid(canvas, solid, color)
        }
        for (solid in grid.dynamic) solid(canvas, solid, MOVING)
    }

    /** Fills one [solid] in [color]: a box whole, a slope one row at a time under its surface. */
    fun solid(canvas: Canvas, solid: Solid, color: Color) {
        val width = solid.right - solid.left
        when (solid.shape) {
            SolidShape.BOX -> rect(canvas, solid.left, solid.top, width, solid.bottom - solid.top, color, layer)
            SolidShape.RISING, SolidShape.FALLING -> {
                var y = solid.top
                while (y < solid.bottom) {
                    // Measured at the row's middle, so a row is filled where the slope covers most of it.
                    val under = solid.bottom - (y + ROW / 2f)
                    if (solid.shape == SolidShape.RISING) {
                        rect(canvas, solid.left + under, y, width - under, ROW, color, layer)
                    } else {
                        rect(canvas, solid.left, y, width - under, ROW, color, layer)
                    }
                    y += ROW
                }
            }
        }
        if (!showDetails) return
        val edge = color.darker()
        when (solid.shape) {
            SolidShape.BOX -> {
                line(canvas, solid.left, solid.top, solid.right, solid.top, EDGE, edge, layer + 2)
                line(canvas, solid.right, solid.top, solid.right, solid.bottom, EDGE, edge, layer + 2)
                line(canvas, solid.right, solid.bottom, solid.left, solid.bottom, EDGE, edge, layer + 2)
                line(canvas, solid.left, solid.bottom, solid.left, solid.top, EDGE, edge, layer + 2)
            }
            SolidShape.RISING -> line(canvas, solid.left, solid.bottom, solid.right, solid.top, EDGE, edge, layer + 2)
            SolidShape.FALLING -> line(canvas, solid.left, solid.top, solid.right, solid.bottom, EDGE, edge, layer + 2)
        }
    }

    /**
     * Shows what [broadphase] found: every cell a box was filed under, tinted once per box filed there so
     * busy cells glow brighter, and a line between the two [particles] of every candidate pair. The
     * boxes' ids must be particle handles.
     */
    fun broadphase(canvas: Canvas, broadphase: Broadphase, particles: Particles, cellColor: Color = CELL, pairColor: Color = PAIR) {
        val size = broadphase.cellSize
        for (e in 0 until broadphase.count) {
            val col0 = floor(broadphase.left(e) / size).toInt()
            val col1 = floor(broadphase.right(e) / size).toInt()
            val row0 = floor(broadphase.top(e) / size).toInt()
            val row1 = floor(broadphase.bottom(e) / size).toInt()
            for (row in row0..row1) for (col in col0..col1) rect(canvas, col * size, row * size, size, size, cellColor, layer - 1)
        }
        for (i in 0 until broadphase.pairCount) {
            val a = broadphase.pairA(i)
            val b = broadphase.pairB(i)
            line(canvas, particles.x(a), particles.y(a), particles.x(b), particles.y(b), EDGE, pairColor, layer + 1)
        }
    }

    /** Writes [text] centred on [x] with its top at [y], in world units, above everything else drawn here. */
    fun label(canvas: Canvas, text: CharSequence, x: Float, y: Float, color: Color = LABEL) {
        val font = font ?: return
        canvas.drawText(font, text, x, y, color, scale = 1f, align = TextAlign.CENTER, layer = layer + 5, space = DrawSpace.WORLD, shadow = LABEL_SHADOW)
    }

    /** A line of [thickness] world units from (x0, y0) to (x1, y1). */
    fun line(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, color: Color, layer: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        canvas.drawSprite(
            pixel,
            x = (x0 + x1) / 2f,
            y = (y0 + y1) / 2f,
            width = sqrt(dx * dx + dy * dy) + thickness,
            height = thickness,
            rotation = atan2(dy, dx),
            color = color,
            layer = layer,
            space = DrawSpace.WORLD,
        )
    }

    private fun rect(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, color: Color, layer: Int) {
        if (width <= 0f || height <= 0f) return
        canvas.drawRect(x, y, width, height, color, layer, DrawSpace.WORLD)
    }

    private fun Color.darker(): Color = Color(red * 0.6f, green * 0.6f, blue * 0.6f, alpha)

    companion object {
        /** How many straight edges make a circle's outline; a capsule's two ends share as many. */
        const val ROUND_STEPS = 24
        private const val MAX_OUTLINE = ROUND_STEPS + 2

        /** Height of one row of a filled shape, in world units. */
        const val ROW = 1f

        const val OUTLINE = 1f
        const val EDGE = 0.5f
        const val DOT = 2f
        const val CORE_DOT = 3f
        const val FOOT = 3f

        val SOLID = Color(0x3A4A66FFu)
        val MOVING = Color(0x7A5A3AFFu)
        val PARTICLE = Color(0xFFFFFFFFu)
        val LINK = Color(0x9FB3D9FFu)
        val ROPE = Color(0xC9A26BFFu)
        val CORE = Color(0xFF4040FFu)
        val FLOOR_CONTACT = Color(0xFFD36BFFu)
        val LABEL = Color(0xE8ECF4FFu)
        val CONTACT = Color(0x5BE0E0FFu)
        val SLACK = Color(0.55f, 0.6f, 0.7f, 0.35f)
        val BOX = Color(0.36f, 0.55f, 0.94f, 0.6f)

        /** How far along a contact's normal its line reaches, in world units. */
        const val NORMAL = 6f

        /** What counts as full stress, drawn fully blue or red: a link 10% off its length... */
        const val FULL_STRAIN = 0.1f

        /** ...a pinned particle 8 units from its point, a bend half a radian off, an area 20% off. */
        const val FULL_PIN = 8f
        const val FULL_BEND = 0.5f
        const val FULL_AREA = 0.2f

        private val SQUEEZED = Color(0x4D7CFFFFu)
        private val RESTING = Color(0xB8C2D6FFu)
        private val STRETCHED = Color(0xFF4D4DFFu)
        private const val PI_F = PI.toFloat()

        /**
         * The colour of a stress of [amount]: -1 or less is fully squeezed (blue), 0 at rest (grey),
         * 1 or more fully stretched (red).
         */
        fun stressColor(amount: Float): Color {
            val k = if (amount.isNaN()) 0f else amount.coerceIn(-1f, 1f)
            val to = if (k < 0f) SQUEEZED else STRETCHED
            val t = abs(k)
            return Color(
                RESTING.red + (to.red - RESTING.red) * t,
                RESTING.green + (to.green - RESTING.green) * t,
                RESTING.blue + (to.blue - RESTING.blue) * t,
                1f,
            )
        }
        val CELL = Color(0.36f, 0.55f, 0.94f, 0.12f)
        val PAIR = Color(0xFF6BB5FFu)

        /** Half a world unit: at the sandbox's zoom, one screen point. */
        private val LABEL_SHADOW = TextShadow(offsetX = 0.5f, offsetY = 0.5f)
    }
}
