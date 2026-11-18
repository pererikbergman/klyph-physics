package klyph.physics.demo

import klyph.math.Rect
import klyph.physics.BodyKind
import klyph.physics.Filter
import klyph.physics.Material
import klyph.physics.PhysicsWorld
import klyph.physics.Shape
import klyph.physics.TouchKind
import klyph.physics.Touches
import klyph.physics.bridge.DebugDraw
import klyph.platform.Color
import klyph.render.Canvas
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A sorting machine made of filters. Red and blue balls, crates and blobs drop onto a ramp. Part of it
 * is a red gate: red things are filtered to pass through it, so they fall into the red bin, and blue
 * ones slide over. Further on a blue gate does the same for blue. A sensor over each bin counts what
 * comes in, from its events, and a flash marks every touch that begins. A right click drops one more.
 */
object SorterScene : Scene {
    override val name = "Sorter"
    override val blurb = "filters let red through the red gate and blue through the blue; sensors count"

    /** The categories: red and blue things, and the gates that only stop the other colour. */
    const val RED = 1 shl 1
    const val BLUE = 1 shl 2
    const val RED_GATE = 1 shl 3
    const val BLUE_GATE = 1 shl 4

    /** Where things drop from, how often, and how many there are at most. */
    const val DROP_X = 70f
    const val DROP_Y = 30f
    const val DROP_EVERY = 0.45f
    const val MAX_ITEMS = 36

    /** The ramp: from ([RAMP_X0], [RAMP_Y0]) down to ([RAMP_X1], [RAMP_Y1]), and how thick. */
    const val RAMP_X0 = 30f
    const val RAMP_Y0 = 70f
    const val RAMP_X1 = 600f
    const val RAMP_Y1 = 263f
    const val RAMP_THICK = 6f

    /** Where along x each gate is: red over the red bin, blue over the blue. */
    const val RED_FROM = 256f
    const val RED_TO = 416f
    const val BLUE_FROM = 440f

    /** The walls under the ramp: the red bin's left, and the one between the bins. */
    const val RED_WALL = 15
    const val DIVIDER = 26

    /** The top of the sensors over the bins: below the end of the ramp, so only what has fallen in is in. */
    const val BIN_TOP = 17 * 16f

    /** How long a touch's flash lasts, in seconds. */
    const val FLASH = 0.35f

    private val RED_COLOR = Color(0xFF5C6CFFu)
    private val BLUE_COLOR = Color(0x5CA8FFFFu)
    private val RAMP = Color(0x9AA6BFFFu)
    private val RED_GATE_COLOR = Color(1f, 0.36f, 0.42f, 0.55f)
    private val BLUE_GATE_COLOR = Color(0.36f, 0.66f, 1f, 0.55f)
    private val SENSOR = Color(1f, 1f, 1f, 0.25f)
    private val TEXT = Color(0xE8ECF5FFu)

    /** The ramp's grip: low, so crates slide and blobs slip. */
    private val SLICK = Material(friction = 0.1f, staticFriction = 0.15f)

    override fun create(): SceneRun = Run()

    internal class Run : SceneRun {
        override val world = PhysicsWorld().apply {
            solids = Scenes.room().apply {
                for (r in 9 until Scenes.ROWS - 2) fill(RED_WALL, r)
                for (r in 13 until Scenes.ROWS - 2) fill(DIVIDER, r)
            }
        }
        private val bodies = world.rigidBodies
        private val floor = (Scenes.ROWS - 2) * Scenes.TILE
        private val slope = (RAMP_Y1 - RAMP_Y0) / (RAMP_X1 - RAMP_X0)

        /** The ramp's pieces, left to right: plain, the red gate, plain, the blue gate. */
        val ramp = intArrayOf(
            section(RAMP_X0, RED_FROM, Filter.DEFAULT),
            section(RED_FROM, RED_TO, RED_GATE),
            section(RED_TO, BLUE_FROM, Filter.DEFAULT),
            section(BLUE_FROM, RAMP_X1, BLUE_GATE),
        )

        /** The sensors over the bins, counting what comes in. */
        val redBin = binSensor(RED_WALL + 1, DIVIDER)
        val blueBin = binSensor(DIVIDER + 1, Scenes.COLS - 1)

        /** Every rigid and soft thing dropped, with whether it's red, oldest first. */
        val items = ArrayList<Item>()

        /** How many of each came into each bin. */
        var redInRed = 0
            private set
        var blueInBlue = 0
            private set
        var wrongBin = 0
            private set

        /** How many touches have begun since the start. */
        var touchesBegun = 0
            private set

        /** The flashes: where, and how long ago. */
        private val flashX = FloatArray(64)
        private val flashY = FloatArray(64)
        private val flashAge = FloatArray(64) { FLASH }
        private var nextFlash = 0

        private var time = 0f
        private var dropped = 0

        class Item(val kind: TouchKind, val handle: Int, val red: Boolean)

        /** A piece of the ramp from [x0] to [x1], in [category]. */
        private fun section(x0: Float, x1: Float, category: Int): Int {
            val y0 = RAMP_Y0 + (x0 - RAMP_X0) * slope
            val y1 = RAMP_Y0 + (x1 - RAMP_X0) * slope
            val length = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            // a hair longer, so its ends overlap the next piece's and nothing catches between them
            val b = world.rigidBody(Shape.box(length + 1f, RAMP_THICK), (x0 + x1) / 2f, (y0 + y1) / 2f, angle = atan2(y1 - y0, x1 - x0), material = SLICK, kind = BodyKind.STATIC)
            bodies.setFilter(b, category, Filter.ALL)
            return b
        }

        /** A sensor over the bin between the walls in columns [leftWall] and [rightWall], from the floor up. */
        private fun binSensor(leftWall: Int, rightWall: Int): Int {
            val left = leftWall * Scenes.TILE
            val right = rightWall * Scenes.TILE
            val top = BIN_TOP
            val b = world.rigidBody(Shape.box(right - left, floor - top), (left + right) / 2f, (top + floor) / 2f, kind = BodyKind.STATIC)
            bodies.setSensor(b, true)
            return b
        }

        override fun beforeStep(dt: Float) {
            time += dt
            if (time >= (dropped + 1) * DROP_EVERY) drop(DROP_X + (dropped % 5 - 2) * 6f, DROP_Y)
        }

        override fun afterStep(dt: Float) {
            for (k in flashAge.indices) flashAge[k] += dt
            count(world.events.sensorsEntered)
            flash(world.events.contactsBegun)
        }

        /** Counts what came into each bin, by colour. */
        private fun count(entered: Touches) {
            for (i in 0 until entered.count) {
                val item = find(entered.kindB(i), entered.b(i)) ?: continue
                val bin = entered.a(i)
                when {
                    bin == redBin && item.red -> redInRed++
                    bin == blueBin && !item.red -> blueInBlue++
                    else -> wrongBin++
                }
            }
        }

        /** A flash where each item that began touching something is. */
        private fun flash(begun: Touches) {
            for (i in 0 until begun.count) {
                touchesBegun++
                val item = find(begun.kindA(i), begun.a(i)) ?: find(begun.kindB(i), begun.b(i)) ?: continue
                flashX[nextFlash] = itemX(item)
                flashY[nextFlash] = itemY(item)
                flashAge[nextFlash] = 0f
                nextFlash = (nextFlash + 1) % flashX.size
            }
        }

        private fun find(kind: TouchKind, handle: Int): Item? {
            for (item in items) if (item.kind == kind && item.handle == handle) return item
            return null
        }

        fun itemX(item: Item): Float = if (item.kind == TouchKind.RIGID_BODY) bodies.x(item.handle) else world.softBodies.centerX(item.handle)

        fun itemY(item: Item): Float = if (item.kind == TouchKind.RIGID_BODY) bodies.y(item.handle) else world.softBodies.centerY(item.handle)

        /** Drops the next thing at ([x], [y]): red and blue by turns, a ball, a crate or a blob. */
        private fun drop(x: Float, y: Float) {
            val red = dropped % 2 == 0
            val category = if (red) RED else BLUE
            val mask = Filter.ALL and (if (red) RED_GATE else BLUE_GATE).inv()
            items += when (dropped / 2 % 3) {
                0 -> Item(TouchKind.RIGID_BODY, world.rigidBody(Shape.circle(8f), x, y).also { bodies.setFilter(it, category, mask) }, red)
                1 -> Item(TouchKind.RIGID_BODY, world.rigidBody(Shape.box(14f, 14f), x, y, angle = 0.3f).also { bodies.setFilter(it, category, mask) }, red)
                else -> Item(TouchKind.SOFT_BODY, world.softBody(Scenes.dome(), x, y + Scenes.DOME_HALF_HEIGHT).also { world.softBodies.setFilter(it, category, mask) }, red)
            }
            dropped++
            if (items.size > MAX_ITEMS) {
                val oldest = items.removeAt(0)
                if (oldest.kind == TouchKind.RIGID_BODY) bodies.remove(oldest.handle) else world.softBodies.remove(oldest.handle)
            }
        }

        override fun spawn(x: Float, y: Float) {
            if (Scenes.isOpen(world, x, y)) drop(x, y)
        }

        override fun draw(canvas: Canvas, debug: DebugDraw, view: Rect) {
            debug.solids(canvas, world.solids!!, view)
            outline(canvas, debug, RED_WALL + 1, DIVIDER)
            outline(canvas, debug, DIVIDER + 1, Scenes.COLS - 1)
            debug.rigidBody(canvas, world, ramp[0], RAMP)
            debug.rigidBody(canvas, world, ramp[1], RED_GATE_COLOR)
            debug.rigidBody(canvas, world, ramp[2], RAMP)
            debug.rigidBody(canvas, world, ramp[3], BLUE_GATE_COLOR)
            for (item in items) {
                val color = if (item.red) RED_COLOR else BLUE_COLOR
                if (item.kind == TouchKind.RIGID_BODY) debug.rigidBody(canvas, world, item.handle, color) else debug.softBody(canvas, world, item.handle, color)
            }
            for (k in flashAge.indices) {
                val age = flashAge[k]
                if (age >= FLASH) continue
                // a ring that grows and fades
                val fade = 1f - age / FLASH
                val r = 4f + 18f * (1f - fade)
                val color = Color(1f, 0.85f, 0.4f, fade)
                val x = flashX[k]
                val y = flashY[k]
                debug.line(canvas, x - r, y - r, x + r, y - r, 1.5f, color, debug.layer)
                debug.line(canvas, x + r, y - r, x + r, y + r, 1.5f, color, debug.layer)
                debug.line(canvas, x + r, y + r, x - r, y + r, 1.5f, color, debug.layer)
                debug.line(canvas, x - r, y + r, x - r, y - r, 1.5f, color, debug.layer)
            }
            debug.label(canvas, "red bin $redInRed", (RED_WALL + 1 + DIVIDER) * Scenes.TILE / 2f, BIN_TOP - 10f, TEXT)
            debug.label(canvas, "blue bin $blueInBlue", (DIVIDER + Scenes.COLS) * Scenes.TILE / 2f, BIN_TOP - 10f, TEXT)
        }

        /** Draws the sensor over the bin between the walls in columns [leftWall] and [rightWall], as an outline open at the floor. */
        private fun outline(canvas: Canvas, debug: DebugDraw, leftWall: Int, rightWall: Int) {
            val left = leftWall * Scenes.TILE
            val right = rightWall * Scenes.TILE
            val top = BIN_TOP
            debug.line(canvas, left, top, right, top, 1f, SENSOR, debug.layer)
            debug.line(canvas, left, top, left, floor, 1f, SENSOR, debug.layer)
            debug.line(canvas, right, top, right, floor, 1f, SENSOR, debug.layer)
        }

        override val particleCount get() = world.particles.count
        override val bodyCount get() = bodies.count
        override val contactCount get() = world.rigidContacts.count
    }
}
