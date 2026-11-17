package klyph.physics

/**
 * Who collides with whom. Everything that collides has a **category**, the bits saying what it is,
 * and a **mask**, the bits of the categories it collides with. Two things collide only if each one's
 * mask has a bit of the other's category: see [collides]. By default everything is in [DEFAULT] and
 * has the mask [ALL], so everything collides with everything.
 *
 * The level, the [SolidGrid], is in category [SOLIDS]: take that bit out of a mask and the thing
 * passes through the solids. Use bits 0 to 29 for your own categories; bit 30 is the solids', and bit
 * 31 is left alone so a category is never negative.
 *
 * ```
 * val PLAYER = 1 shl 1
 * val GHOST = 1 shl 2
 * world.rigidBodies.setFilter(ghost, category = GHOST, mask = Filter.ALL and PLAYER.inv())
 * ```
 */
object Filter {
    /** The category everything is in until it's given another. */
    const val DEFAULT = 1

    /** The level's category: everything with this bit in its mask is kept out of the solids. */
    const val SOLIDS = 1 shl 30

    /** Every category: the mask everything has until it's given another. */
    const val ALL = -1

    /** Whether something of [categoryA] and [maskA] collides with something of [categoryB] and [maskB]. */
    fun collides(categoryA: Int, maskA: Int, categoryB: Int, maskB: Int): Boolean =
        categoryA and maskB != 0 && categoryB and maskA != 0

    internal fun checkCategory(category: Int) {
        require(category != 0 && category and (SOLIDS or (1 shl 31)) == 0) {
            "category is $category: it must have a bit, and only bits 0 to 29, as bit 30 is the solids'"
        }
    }
}

/** What one side of a touch is: see [Touches]. */
enum class TouchKind {
    RIGID_BODY,
    SOFT_BODY,

    /** A particle that isn't part of a soft body, such as a grain of sand. */
    PARTICLE,

    /** The level: the [PhysicsWorld.solids]. Its handle is -1. */
    SOLIDS,
}

/**
 * A list of touches, each between two things: [kindA] [a] and [kindB] [b]. Filled in by [Events] each
 * step; read it, don't keep it, as the next step writes over it. Indexing allocates nothing.
 */
class Touches internal constructor() {
    private var keys = LongArray(16)

    /** How many touches there are. */
    var count: Int = 0
        private set

    /** What the first side of touch [i] is. For a sensor's touch, the sensor. */
    fun kindA(i: Int): TouchKind = TouchKind.entries[TouchPairs.kindA(keys[i])]

    /** The first side's handle: a rigid body, a soft body or a particle. */
    fun a(i: Int): Int = TouchPairs.a(keys[i])

    /** What the second side of touch [i] is. */
    fun kindB(i: Int): TouchKind = TouchKind.entries[TouchPairs.kindB(keys[i])]

    /** The second side's handle, or -1 for [TouchKind.SOLIDS]. */
    fun b(i: Int): Int = TouchPairs.b(keys[i])

    /** Whether any touch has [kind] [handle] on either side. */
    fun involves(kind: TouchKind, handle: Int): Boolean {
        for (i in 0 until count) {
            val k = keys[i]
            if (TouchPairs.kindA(k) == kind.ordinal && TouchPairs.a(k) == handle) return true
            if (TouchPairs.kindB(k) == kind.ordinal && TouchPairs.b(k) == handle) return true
        }
        return false
    }

    /** Whether there's a touch between [kindA] [a] and [kindB] [b], either way round. */
    fun has(kindA: TouchKind, a: Int, kindB: TouchKind, b: Int): Boolean {
        val one = TouchPairs.key(kindA.ordinal, a, kindB.ordinal, b)
        val other = TouchPairs.key(kindB.ordinal, b, kindA.ordinal, a)
        for (i in 0 until count) if (keys[i] == one || keys[i] == other) return true
        return false
    }

    internal fun clear() {
        count = 0
    }

    internal fun add(key: Long) {
        if (count == keys.size) keys = keys.copyOf(count * 2)
        keys[count++] = key
    }
}

/**
 * A set of touches, each packed into a `Long`: an open-addressed hash table plus the order they came
 * in, so adding is cheap however often the same touch is seen in a step, and clearing costs only
 * what was added.
 */
internal class TouchPairs {
    private var table = LongArray(64) { EMPTY }
    private var keys = LongArray(32)

    var count = 0
        private set

    fun key(i: Int): Long = keys[i]

    fun add(key: Long) {
        if (2 * (count + 1) > table.size) grow()
        val mask = table.size - 1
        var slot = hash(key) and mask
        while (true) {
            val k = table[slot]
            if (k == key) return
            if (k == EMPTY) break
            slot = (slot + 1) and mask
        }
        table[slot] = key
        if (count == keys.size) keys = keys.copyOf(count * 2)
        keys[count++] = key
    }

    fun contains(key: Long): Boolean {
        val mask = table.size - 1
        var slot = hash(key) and mask
        while (true) {
            val k = table[slot]
            if (k == key) return true
            if (k == EMPTY) return false
            slot = (slot + 1) and mask
        }
    }

    fun clear() {
        if (count == 0) return
        val mask = table.size - 1
        // only the slots that were used, found as they were filled
        for (i in 0 until count) {
            var slot = hash(keys[i]) and mask
            while (table[slot] != EMPTY) {
                table[slot] = EMPTY
                slot = (slot + 1) and mask
            }
        }
        count = 0
    }

    private fun grow() {
        val old = keys
        val n = count
        table = LongArray(table.size * 2) { EMPTY }
        count = 0
        for (i in 0 until n) add(old[i])
    }

    companion object {
        private const val EMPTY = -1L

        /**
         * Mixes every bit of [key] into the low ones, which pick the slot: a touch with the solids has
         * the same low 29 bits as every other, so a plain multiply crowds them all into a few slots.
         * MurmurHash3's finaliser.
         */
        private fun hash(key: Long): Int {
            var h = key
            h = (h xor (h ushr 33)) * -49_064_778_989_728_563L
            h = (h xor (h ushr 33)) * -4_265_267_296_055_464_877L
            return (h xor (h ushr 33)).toInt()
        }

        /** Packs a touch: two bits of kind and 29 of handle a side, the solids' handle as 0, never negative. */
        fun key(kindA: Int, a: Int, kindB: Int, b: Int): Long =
            (kindA.toLong() shl 61) or ((a.toLong() and HANDLE) shl 32) or (kindB.toLong() shl 29) or (b.toLong() and HANDLE)

        /** The same touch whichever side comes first: the lower kind first, then the lower handle. */
        fun ordered(kindA: Int, a: Int, kindB: Int, b: Int): Long =
            if (kindA < kindB || kindA == kindB && a <= b) key(kindA, a, kindB, b) else key(kindB, b, kindA, a)

        fun kindA(key: Long): Int = (key ushr 61).toInt() and 3

        fun a(key: Long): Int = ((key ushr 32) and HANDLE).toInt()

        fun kindB(key: Long): Int = (key ushr 29).toInt() and 3

        fun b(key: Long): Int = if (kindB(key) == TouchKind.SOLIDS.ordinal) -1 else (key and HANDLE).toInt()

        private const val HANDLE = (1L shl 29) - 1
    }
}
