package commandcenter;

import integration.BusConnection;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Process 1: the panel and its own unchanged Team 3 Software Bus (SB). */
public class Main extends Application {
    private SoftwareBusManager manager;

    @Override
    public void start(Stage stage) {
        BusConnection bus = BusConnection.open(6000, getParameters().getRaw().toArray(new String[0]));
        CommandCenterView view = new CommandCenterView();
        manager = new SoftwareBusManager(bus.host, bus.port);
        manager.start(view);

        stage.setTitle("Command Center — SB");
        stage.setScene(new Scene(view.getRoot()));
        stage.setResizable(false);
        stage.show();
    }

    @Override
    public void stop() {
        if (manager != null) manager.stop();
    }

    public static void main(String[] args) {
        launch(args);
        // Upstream bus threads have no shutdown API and live until process exit.
        System.exit(0);
    }
}
