package commandcenter;

import SoftwareBus.MessageBus;
import SoftwareBus.SoftwareBusServer;
import java.io.IOException;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Initializes the system: creates the screen and a manager and connects them.
 * Selects the software bus manager or the demo manager from launch arguments.
 */
public class Main extends Application {
    private Manager manager;

    static Manager createManager(List<String> args) {
        if (args.equals(List.of("--demo"))) return new TestManager();
        boolean busDemo = args.equals(List.of("--bus-demo"));
        if (busDemo) args = List.of();
        if (!args.isEmpty() && args.size() != 2) {
            throw new IllegalArgumentException("Usage: Main [--demo | --bus-demo | busHost busPort]");
        }
        String host = args.isEmpty() ? "localhost" : args.get(0);
        int port = args.isEmpty() ? 6000 : Integer.parseInt(args.get(1));
        SoftwareBusManager result = new SoftwareBusManager(host, port,
                busDemo ? BusCodes.IN_SERVICE : codeProperty("bus.inServiceCode", BusCodes.IN_SERVICE),
                busDemo ? BusCodes.OUT_OF_SERVICE : codeProperty("bus.outOfServiceCode", BusCodes.OUT_OF_SERVICE),
                busDemo ? BusCodes.FIRE_TEST : codeProperty("bus.fireTestCode", BusCodes.FIRE_TEST));
        if (args.isEmpty()) {
            Thread bus = new Thread(() -> {
                try {
                    new SoftwareBusServer(port, new MessageBus()).start();
                } catch (IOException e) {
                    System.err.println("Local software bus stopped: " + e.getMessage());
                }
            }, "software-bus");
            bus.setDaemon(true);
            bus.start();
        }
        if (!busDemo) return result;
        BusTestController controller = new BusTestController(host, port);
        return new Manager() {
            @Override public void start(CommandCenterApi api) {
                result.start(api);
                controller.start();
            }
            @Override public void stop() {
                controller.close();
                result.stop();
            }
        };
    }

    private static long codeProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    @Override
    public void start(Stage stage) {
        CommandCenterView view = new CommandCenterView();
        manager = createManager(getParameters().getRaw());
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
