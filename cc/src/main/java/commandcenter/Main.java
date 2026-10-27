package commandcenter;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Initializes the system: creates the screen and a manager and connects them.
 * Replacing the manager (e.g. with a Software Bus manager) only changes {@link #createManager()}.
 */
public class Main extends Application {
    private Manager manager;

    static Manager createManager() {
        return new TestManager();
    }

    @Override
    public void start(Stage stage) {
        CommandCenterView view = new CommandCenterView();
        manager = createManager();
        view.setCommandListener(manager);
        manager.start(view);

        stage.setTitle("Command Center");
        stage.setScene(new Scene(view.getRoot()));
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> { manager.stop(); Platform.exit(); });
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
