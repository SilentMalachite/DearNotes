package app.dearnotes.ui;

import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.scene.control.Alert;

/** データベースの操作を UI から呼び、失敗したらダイアログで知らせる。 */
final class Errors {

    private static final Logger LOG = Logger.getLogger(Errors.class.getName());

    @FunctionalInterface
    interface SqlAction {
        void run() throws SQLException;
    }

    @FunctionalInterface
    interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private Errors() {}

    static boolean run(String what, SqlAction action) {
        try {
            action.run();
            return true;
        } catch (SQLException e) {
            show(what, e);
            return false;
        }
    }

    static <T> Optional<T> get(String what, SqlSupplier<T> supplier) {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (SQLException e) {
            show(what, e);
            return Optional.empty();
        }
    }

    static void show(String what, Exception e) {
        LOG.log(Level.WARNING, what, e);
        var alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("DearNotes");
        alert.setHeaderText(what + "できませんでした");
        alert.setContentText(e.getMessage());
        alert.show();
    }
}
