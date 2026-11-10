package klyph.physics.demo

/** Every scene of the sandbox, in the order the number keys choose them. */
object Scenes {
    val all: List<Scene> = listOf(EmptyScene)
}

/** A room with nothing in it, so the sandbox's controls can be tried before there is physics to show. */
object EmptyScene : Scene {
    override val name = "Empty Room"
    override val blurb = "nothing here yet. pause, step and slow it down anyway"

    override fun create(): SceneRun = object : SceneRun {
        override fun step(dt: Float) {}
    }
}
