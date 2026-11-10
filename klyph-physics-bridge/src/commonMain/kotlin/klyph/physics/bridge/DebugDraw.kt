package klyph.physics.bridge

import klyph.math.Rect
import klyph.physics.Constraints
import klyph.physics.Outline
import klyph.physics.Particles
import klyph.physics.PhysicsWorld
import klyph.physics.Ring
import klyph.physics.Solid
import klyph.physics.SolidGrid
import klyph.physics.SolidShape
import klyph.platform.Color
import klyph.render.Canvas
import klyph.render.DrawSpace
import klyph.render.TextureRegion
import klyph.text.Font
import klyph.text.TextAlign
import klyph.text.TextShadow
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws what the physics sees, in world space, on a klyph [Canvas]: soft bodies filled from their
 * outlines, and the solids they collide with. With [showDetails] it also draws every particle (the
 * core in red), the edge of every tile and every particle standing on a floor; with it off, none of
 * that work is done.
 *
 * klyph has no polygons, so shapes are filled one row of [ROW] world units at a time, and lines are
 * stretched, turned quads of [pixel] (`App.assets.whitePixel`). Labels need a [font], such as
 * `App.assets.defaultFont`; without one they are not drawn.
 */
class DebugDraw(private val pixel: TextureRegion, private val font: Font? = null) {
    /** Whether to draw particles, tile edges and floor contacts on top of the plain view. */
    var showDetails: Boolean = false

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

    /** With details, marks every particle of [world] that stood on a floor during the last step. */
    fun contacts(canvas: Canvas, world: PhysicsWorld) {
        if (!showDetails) return
        val ps = world.particles
        for (p in 0 until ps.end) {
            if (!ps.isAlive(p) || !world.events.isOnFloor(p)) continue
            rect(canvas, ps.x(p) - FOOT / 2f, ps.y(p) + ps.radius(p) + 1f, FOOT, 1.5f, FLOOR_CONTACT, layer + 4)
        }
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

        /** Half a world unit: at the sandbox's zoom, one screen point. */
        private val LABEL_SHADOW = TextShadow(offsetX = 0.5f, offsetY = 0.5f)
    }
}
