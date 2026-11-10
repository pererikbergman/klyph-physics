package klyph.physics.demo

/**
 * Says which steps of a recorded run to save as pictures, and where: every [every]th step, as
 * `0001.png`, `0002.png`, ... in [directory], so a tool such as ffmpeg can make a GIF of them.
 *
 * A recording run steps the physics once per frame instead of on the clock, so the pictures are
 * the same every time, however fast the machine draws.
 */
class Recording(val every: Int, val directory: String) {
    init {
        require(every >= 1) { "Recording every $every steps: it must be at least 1" }
        require(directory.isNotEmpty()) { "Recording directory is empty" }
    }

    /** The file to save after step number [step], counting from 1, or `null` when that step isn't saved. */
    fun fileAfter(step: Long): String? {
        if (step < 1 || step % every != 0L) return null
        val number = (step / every).toString().padStart(4, '0')
        return "$directory/$number.png"
    }

    companion object {
        /**
         * Reads a `SANDBOX_RECORD` value of the form `<every>:<directory>`, such as `2:/tmp/frames`,
         * or returns `null` when [value] is `null` or doesn't have that form.
         */
        fun parse(value: String?): Recording? {
            if (value == null) return null
            val colon = value.indexOf(':')
            if (colon < 0) return null
            val every = value.substring(0, colon).trim().toIntOrNull() ?: return null
            val directory = value.substring(colon + 1).trimEnd('/')
            if (every < 1 || directory.isEmpty()) return null
            return Recording(every, directory)
        }
    }
}
