package app.dearnotes;

import javafx.application.Application;

/**
 * jar の main。JavaFX をクラスパスから読むときは、Application を継承しないクラスから起動する。
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
