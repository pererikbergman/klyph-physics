package klyph.physics.demo

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A mouse for pictures, which no one has to hold: at step [GRAB_AT] it grabs what is nearest ([x],
 * [y]), lifts it [LIFT] units over [LIFT_STEPS] steps, swings it from side to side, and lets go at
 * step [RELEASE_AT]. `SANDBOX_DRAG=x,y` turns it on; `scripts/capture.sh --drag x,y` sets that.
 */
class ScriptedHand(val x: Float, val y: Float) {
    /** Moves the hand for the step the sandbox is about to take. Call it before every [Sandbox.fixedStep]. */
    fun drive(sandbox: Sandbox) {
        val step = sandbox.stepCount
        when {
            step == GRAB_AT -> sandbox.grab(x, y)
            step in GRAB_AT + 1 until RELEASE_AT -> {
                val k = (step - GRAB_AT).toFloat()
                val lift = LIFT * min(1f, k / LIFT_STEPS)
                val swing = SWING * sin(2f * PI.toFloat() * k / SWING_STEPS)
                sandbox.moveGrab(x + swing, y - lift)
            }
            step == RELEASE_AT -> sandbox.release()
        }
    }

    companion object {
        const val GRAB_AT = 30L
        const val RELEASE_AT = 210L
        const val LIFT = 90f
        const val LIFT_STEPS = 40f
        const val SWING = 110f
        const val SWING_STEPS = 90f

        /** Reads a `SANDBOX_DRAG` value such as `320,200`, or returns `null` when it isn't two numbers. */
        fun parse(value: String?): ScriptedHand? {
            val parts = value?.split(',') ?: return null
            if (parts.size != 2) return null
            val x = parts[0].trim().toFloatOrNull() ?: return null
            val y = parts[1].trim().toFloatOrNull() ?: return null
            return ScriptedHand(x, y)
        }
    }
}
