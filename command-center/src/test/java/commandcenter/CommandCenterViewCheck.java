package commandcenter;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.util.Duration;

// Checks actual button behavior and captures the panel without a test controller.
public class CommandCenterViewCheck extends Application {
    @Override public void start(Stage stage) {
        CommandCenterView view = new CommandCenterView();
        GridPane root = (GridPane) view.getRoot();
        stage.setScene(new Scene(root));
        stage.setTitle("Command Center UI check");
        stage.show();
        Platform.runLater(() -> {
            try {
                check(text(root, 0, 2).equals("Service: Unknown"), "initial service is unknown");
                check(text(root, 0, 8).equals("Door: Unknown"), "initial door is unknown");
                check(text(root, 3, 0).equals("Fire: Unknown"), "initial fire is unknown");
                check(button(root, 0, 2).isDisabled(), "service is disabled until status arrives");
                for (int id = 1; id <= CommandCenterApi.ELEVATORS; id++) {
                    view.setCurrentFloor(id, id);
                    view.setDirection(id, id == 4 ? Direction.DOWN : Direction.NONE);
                    view.setDoorStatus(id, id == 1 ? DoorStatus.TRANSITION : id == 2 ? DoorStatus.OPEN : DoorStatus.CLOSED);
                    view.setObstruction(id, id == 2);
                    view.setOverload(id, id == 2);
                    view.setServiceStatus(id, id == 3 ? ServiceStatus.OUT_OF_SERVICE : ServiceStatus.IN_SERVICE);
                }
                view.setFireStatus(FireStatus.ALARM);
                check(text(root, 0, 8).equals("Door: Transition"), "transition is displayed");
                Path output = Path.of(getParameters().getRaw().isEmpty() ? "docs" : getParameters().getRaw().get(0));
                Files.createDirectories(output);
                capture(root, output.resolve("command-center.png"));

                Button service = button(root, 0, 2);
                service.fire();
                check(view.takeOutOfService(1) && !view.takeOutOfService(1), "service press is consumed once");
                check(service.isDisabled(), "pending service button is disabled");
                view.setServiceStatus(1, ServiceStatus.IN_SERVICE);
                check(service.isDisabled(), "old service snapshot does not complete a request");
                view.setServiceStatusFresh(1, false);
                check(service.isDisabled(), "retry waits for fresh service status");
                view.setServiceStatus(1, ServiceStatus.IN_SERVICE);
                check(!service.isDisabled() && service.getText().equals("Retry out of service"),
                        "recovered status allows an unconfirmed request to be retried");
                service.fire();
                check(view.takeOutOfService(1) && !view.returnToService(1), "retry sends the same target state");
                check(service.isDisabled(), "retried command waits for confirmation");
                view.setServiceStatus(1, ServiceStatus.OUT_OF_SERVICE);
                check(!service.isDisabled() && service.getText().equals("Return to service"),
                        "matching service confirmation completes the request");
                button(root, 1, 0).fire();
                check(view.fireTest() && !view.fireTest(), "fire test press is consumed once");
                button(root, 0, 0).fire();
                check(view.fireReset() && !view.fireReset(), "fire reset press is consumed once");
                view.setCabinStatusFresh(1, false);
                view.setDoorStatusFresh(1, false);
                view.setServiceStatusFresh(1, false);
                view.setFireStatusFresh(false);
                check(text(root, 0, 4).equals("–") && text(root, 0, 8).equals("Door: Stale"),
                        "stale floor and door are not shown as current");
                check(service.isDisabled(), "stale service cannot be operated");
                check(text(root, 3, 0).equals("Fire: ALARM (stale)"), "stale alarm remains visible");
                capture(root, output.resolve("command-center-stale.png"));
                Button delayedService = button(root, 1, 2);
                delayedService.fire();
                check(view.takeOutOfService(2) && delayedService.isDisabled(), "unconfirmed request starts pending");
                PauseTransition wait = new PauseTransition(Duration.seconds(16));
                wait.setOnFinished(ev -> {
                    try {
                        check(!delayedService.isDisabled() && delayedService.getText().equals("Retry out of service"),
                                "unconfirmed request allows retry after its deadline");
                        delayedService.fire();
                        check(view.takeOutOfService(2) && !view.returnToService(2), "deadline retry keeps the same target");
                        view.setServiceStatus(2, ServiceStatus.OUT_OF_SERVICE);
                        check(delayedService.getText().equals("Return to service"), "retry ends on matching confirmation");
                        System.out.println("Command Center UI checks passed; screenshots: " + output.toAbsolutePath());
                        stage.close();
                        Platform.exit();
                    } catch (AssertionError e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                });
                wait.play();
            } catch (Exception | AssertionError e) {
                e.printStackTrace();
                System.exit(1);
            }
        });
    }

    private static Labeled cell(GridPane root, int col, int row) {
        for (Node node : root.getChildren()) {
            int c = GridPane.getColumnIndex(node) == null ? 0 : GridPane.getColumnIndex(node);
            int r = GridPane.getRowIndex(node) == null ? 0 : GridPane.getRowIndex(node);
            if (c == col && r == row) return (Labeled) node;
        }
        throw new AssertionError("Missing cell " + col + ", " + row);
    }

    private static String text(GridPane root, int col, int row) { return cell(root, col, row).getText(); }

    private static Button button(GridPane root, int col, int row) { return (Button) cell(root, col, row); }

    private static void capture(GridPane root, Path file) throws Exception {
        root.applyCss();
        root.layout();
        WritableImage image = root.snapshot(null, null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) {
            for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        ImageIO.write(png, "png", file.toFile());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    public static void main(String[] args) { launch(args); }
}
