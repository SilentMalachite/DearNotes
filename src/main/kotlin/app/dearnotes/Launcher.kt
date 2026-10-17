package app.dearnotes

import javafx.application.Application

/**
 * jar の main。JavaFX をクラスパスから読むときは、Application を継承しないクラスから起動する。
 */
object Launcher {

    @JvmStatic
    fun main(args: Array<String>) {
        Application.launch(App::class.java, *args)
    }
}
