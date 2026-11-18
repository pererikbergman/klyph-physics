package klyph.physics

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BroadphaseTest {
    private val bp = Broadphase(cellSize = 10f)

    private fun pairs(): Set<Pair<Int, Int>> = (0 until bp.pairCount).map { bp.pairA(it) to bp.pairB(it) }.toSet()

    @Test
    fun twoOverlappingBoxesAreAPairAndAThirdFarAwayIsNot() {
        bp.add(7, 0f, 0f, 5f, 5f)
        bp.add(8, 4f, 4f, 9f, 9f)
        bp.add(9, 100f, 100f, 105f, 105f)
        bp.build()
        assertEquals(setOf(7 to 8), pairs())
    }

    @Test
    fun aPairThatSharesManyCellsIsReportedOnce() {
        bp.add(1, 0f, 0f, 45f, 45f)
        bp.add(2, 5f, 5f, 40f, 40f)
        bp.build()
        assertEquals(1, bp.pairCount)
        assertTrue(bp.filedCount >= 2 * 16, "both are filed under many cells: ${bp.filedCount}")
    }

    @Test
    fun boxesInNeighbouringCellsThatDoNotOverlapAreNotAPair() {
        bp.add(1, 0f, 0f, 9f, 9f)
        bp.add(2, 9.5f, 0f, 15f, 9f)
        bp.build()
        assertEquals(0, bp.pairCount)
    }

    @Test
    fun boxesThatOnlyTouchAreAPair() {
        bp.add(1, 0f, 0f, 10f, 10f)
        bp.add(2, 10f, 0f, 20f, 10f)
        bp.build()
        assertEquals(1, bp.pairCount)
    }

    @Test
    fun negativeCoordinatesWorkToo() {
        bp.add(1, -25f, -25f, -15f, -15f)
        bp.add(2, -16f, -16f, -5f, -5f)
        bp.add(3, 15f, 15f, 25f, 25f)
        bp.build()
        assertEquals(setOf(1 to 2), pairs())
    }

    @Test
    fun aMovingBoxCoversWhereItWillBe() {
        // a fast particle at x = 0 that will move 50 to the right meets one waiting at x = 40
        bp.addMoving(1, 0f, 0f, dx = 50f, dy = 0f, radius = 1f)
        bp.addMoving(2, 40f, 0f, dx = 0f, dy = 0f, radius = 1f)
        bp.build()
        assertEquals(1, bp.pairCount)
        assertEquals(-1f, bp.left(0))
        assertEquals(51f, bp.right(0))
    }

    @Test
    fun clearingForgetsTheBoxesAndThePairs() {
        bp.add(1, 0f, 0f, 5f, 5f)
        bp.add(2, 1f, 1f, 5f, 5f)
        bp.build()
        bp.clear()
        bp.build()
        assertEquals(0, bp.count)
        assertEquals(0, bp.pairCount)
    }

    @Test
    fun itFindsExactlyThePairsBruteForceFindsOnRandomScenes() {
        val random = Random(42)
        repeat(20) { scene ->
            bp.clear()
            val n = 50 + random.nextInt(400)
            val boxes = Array(n) {
                val x = random.nextFloat() * 400f - 200f
                val y = random.nextFloat() * 400f - 200f
                // mostly small, now and then one many cells wide
                val w = if (random.nextInt(20) == 0) random.nextFloat() * 80f else random.nextFloat() * 8f
                val h = if (random.nextInt(20) == 0) random.nextFloat() * 80f else random.nextFloat() * 8f
                floatArrayOf(x, y, x + w, y + h)
            }
            for ((i, b) in boxes.withIndex()) bp.add(1000 + i, b[0], b[1], b[2], b[3])
            bp.build()
            val expected = mutableSetOf<Pair<Int, Int>>()
            for (i in 0 until n) for (j in i + 1 until n) {
                val a = boxes[i]
                val b = boxes[j]
                if (a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]) expected += (1000 + i) to (1000 + j)
            }
            assertEquals(expected.size, bp.pairCount, "scene $scene: no pair twice")
            assertEquals(expected, pairs(), "scene $scene")
        }
    }

    @Test
    fun twoPassiveBoxesAreNeverAPair() {
        bp.add(1, 0f, 0f, 9f, 9f)
        bp.add(2, 1f, 1f, 8f, 8f, passive = true)
        bp.add(3, 2f, 2f, 7f, 7f, passive = true)
        bp.build()
        assertEquals(setOf(1 to 2, 1 to 3), pairs())
    }

    @Test
    fun withoutActiveWithActiveEveryPairIsOneOfEach() {
        val b = Broadphase(cellSize = 10f, activeWithActive = false)
        b.add(1, 0f, 0f, 9f, 9f)
        b.add(2, 1f, 1f, 8f, 8f)
        b.add(3, 2f, 2f, 7f, 7f, passive = true)
        b.add(4, 3f, 3f, 6f, 6f, passive = true)
        b.build()
        assertEquals(setOf(1 to 3, 1 to 4, 2 to 3, 2 to 4), (0 until b.pairCount).map { b.pairA(it) to b.pairB(it) }.toSet())
    }

    @Test
    fun autoCellsAreAsWideAsTheAverageBoxsLongerSide() {
        val b = Broadphase()
        assertTrue(b.isAuto)
        b.add(1, 0f, 0f, 4f, 2f)
        b.add(2, 10f, 10f, 12f, 18f)
        b.build()
        assertEquals(6f, b.cellSize)
    }

    @Test
    fun withAutoCellsAndPassiveBoxesItStillFindsWhatBruteForceFinds() {
        val random = Random(43)
        repeat(20) { scene ->
            val activeWithActive = scene % 2 == 0
            val b = Broadphase(activeWithActive = activeWithActive)
            val n = 50 + random.nextInt(400)
            // grains and crates: many small boxes, a few big ones, in any order, a third passive
            val boxes = Array(n) {
                val x = random.nextFloat() * 300f
                val y = random.nextFloat() * 300f
                val size = if (random.nextInt(10) == 0) 10f + random.nextFloat() * 30f else 1f + random.nextFloat() * 3f
                floatArrayOf(x, y, x + size, y + size)
            }
            val passive = BooleanArray(n) { random.nextInt(3) == 0 }
            for (i in 0 until n) b.add(i, boxes[i][0], boxes[i][1], boxes[i][2], boxes[i][3], passive[i])
            b.build()
            val expected = mutableSetOf<Pair<Int, Int>>()
            for (i in 0 until n) for (j in i + 1 until n) {
                if (passive[i] && passive[j]) continue
                if (!activeWithActive && !passive[i] && !passive[j]) continue
                val p = boxes[i]
                val q = boxes[j]
                if (p[0] <= q[2] && q[0] <= p[2] && p[1] <= q[3] && q[1] <= p[3]) expected += i to j
            }
            assertEquals(expected.size, b.pairCount, "scene $scene: no pair twice")
            assertEquals(expected, (0 until b.pairCount).map { b.pairA(it) to b.pairB(it) }.toSet(), "scene $scene")
        }
    }

    @Test
    fun theSameBoxesGiveTheSamePairsInTheSameOrder() {
        fun run(): List<Pair<Int, Int>> {
            val b = Broadphase(cellSize = 8f)
            val random = Random(7)
            repeat(300) { b.addMoving(it, random.nextFloat() * 200f, random.nextFloat() * 200f, 3f, -2f, 2f) }
            b.build()
            return (0 until b.pairCount).map { b.pairA(it) to b.pairB(it) }
        }
        assertEquals(run(), run())
    }

    @Test
    fun aCellNeedsASizeOrAuto() {
        assertFailsWith<IllegalArgumentException> { Broadphase(cellSize = -1f) }
        assertFailsWith<IllegalArgumentException> { Broadphase(cellSize = Float.NaN) }
        assertTrue(Broadphase(cellSize = Broadphase.AUTO).isAuto)
    }
}
