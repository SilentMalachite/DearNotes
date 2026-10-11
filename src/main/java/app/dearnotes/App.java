package app.dearnotes;

import app.dearnotes.db.BlockRepo;
import app.dearnotes.db.DataDir;
import app.dearnotes.db.Database;
import app.dearnotes.db.PageRepo;
import app.dearnotes.db.SearchRepo;
import app.dearnotes.save.AutoSaver;
import app.dearnotes.ui.MainWindow;
import app.dearnotes.ui.Services;
import app.dearnotes.ui.Welcome;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public final class App extends Application {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    private Database db;
    private AutoSaver saver;
    private MainWindow window;

    @Override
    public void start(Stage stage) {
        try {
            Path file = DataDir.databaseFile();
            LOG.info("database: " + file);
            db = Database.open(file);
            var pages = new PageRepo(db);
            var blocks = new BlockRepo(db);
            long cutoff = System.currentTimeMillis() - PageRepo.TRASH_RETENTION.toMillis();
            int purged = pages.purgeTrashedBefore(cutoff);
            if (purged > 0) LOG.info("ゴミ箱から " + purged + " ページを消した");
            Welcome.createIfEmpty(pages, blocks);

            saver = new AutoSaver(blocks, Platform::runLater);
            var services = new Services(db, pages, blocks, new SearchRepo(db), saver,
                url -> getHostServices().showDocument(url));
            window = new MainWindow(stage, services);
            window.openInitialPage();
            stage.show();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "起動に失敗", e);
            var alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("DearNotes");
            alert.setHeaderText("DearNotes を起動できませんでした");
            alert.setContentText(String.valueOf(e.getMessage()));
            alert.showAndWait();
            Platform.exit();
        }
    }

    /** ウィンドウを閉じたら、未保存の変更を書いてから DB を閉じる。 */
    @Override
    public void stop() {
        // Cmd+Q などウィンドウの close request を通らない終わり方でも、名前を書いておく
        if (window != null) window.flushTitle();
        if (saver != null) saver.close();
        if (db != null) {
            try {
                db.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "DB を閉じられなかった", e);
            }
        }
    }
}
