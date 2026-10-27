package commandcenter;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;

/** JavaFX Command Center screen. All widgets sit on one GridPane; the API methods are thread-safe. */
public class CommandCenterView implements CommandCenterApi {
    private static final String CSS_LABEL = "-fx-text-fill: #555;";

    private final GridPane root = new GridPane();
    private final Label fireLabel = new Label("Fire: Normal");
    private final Panel[] panels = new Panel[ELEVATORS];
    private CommandListener listener;

    private final class Panel {
        final int id;
        final Label title;
        final Polygon up = arrow(true), down = arrow(false);
        final Label floor = new Label("-");
        final Label dirText = new Label("Stopped");
        final Label doorText = new Label("Door: Closed");
        final Circle obstruction = lamp(), overload = lamp();
        final Button service = new Button("Take out of service");
        ServiceStatus status = ServiceStatus.IN_SERVICE;
        boolean pending;

        Panel(int id) {
            this.id = id;
            title = new Label("Elevator " + id);
            title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            floor.setStyle("-fx-background-color: #222; -fx-text-fill: white; -fx-font-size: 36px; -fx-font-weight: bold;");
            floor.setMinSize(64, 64);
            floor.setAlignment(Pos.CENTER);
            service.setMaxWidth(Double.MAX_VALUE);
            service.setOnAction(e -> {
                if (listener == null) return;
                listener.onServiceCommand(id, status == ServiceStatus.IN_SERVICE
                        ? ServiceStatus.OUT_OF_SERVICE : ServiceStatus.IN_SERVICE);
            });
        }
    }

    public CommandCenterView() {
        root.setHgap(12);
        root.setVgap(8);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-background-color: #f4f4f4;");
        for (int c = 0; c < ELEVATORS; c++) {
            ColumnConstraints cc = new ColumnConstraints(170);
            cc.setHgrow(Priority.NEVER);
            root.getColumnConstraints().add(cc);
        }

        // Row 0: top bar (fire controls and status)
        Button fireTest = new Button("Fire Test");
        Button fireReset = new Button("Fire Reset");
        fireTest.setMaxWidth(Double.MAX_VALUE);
        fireReset.setMaxWidth(Double.MAX_VALUE);
        fireTest.setOnAction(e -> { if (listener != null) listener.onFireTest(); });
        fireReset.setOnAction(e -> { if (listener != null) listener.onFireReset(); });
        fireLabel.setStyle("-fx-font-weight: bold;");
        fireLabel.setMaxWidth(Double.MAX_VALUE);
        fireLabel.setAlignment(Pos.CENTER);
        root.add(fireTest, 0, 0);
        root.add(fireReset, 1, 0);
        root.add(fireLabel, 2, 0, 2, 1);

        // Rows 1..8: one column per elevator, identical row layout in each column
        for (int i = 0; i < ELEVATORS; i++) {
            Panel p = panels[i] = new Panel(i + 1);
            int col = i;
            root.add(center(p.title), col, 1);
            root.add(center(p.up), col, 2);
            root.add(center(p.floor), col, 3);
            root.add(center(p.down), col, 4);
            root.add(center(p.dirText), col, 5);
            root.add(center(p.doorText), col, 6);
            root.add(center(row(p.obstruction, "Obstruction", p.overload, "Overload")), col, 7);
            root.add(p.service, col, 8);
        }
        for (int i = 1; i <= ELEVATORS; i++) {
            render(i);
        }
    }

    public GridPane getRoot() { return root; }

    @Override public void setCommandListener(CommandListener l) { this.listener = l; }

    // ---- CommandCenterApi -------------------------------------------------

    @Override public void setCurrentFloor(int id, int floor) {
        ui(() -> panel(id).floor.setText(Integer.toString(floor)));
    }

    @Override public void setDirection(int id, Direction d) {
        ui(() -> {
            Panel p = panel(id);
            p.up.setFill(d == Direction.UP ? Color.BLACK : Color.WHITE);
            p.down.setFill(d == Direction.DOWN ? Color.BLACK : Color.WHITE);
            p.dirText.setText(switch (d) { case UP -> "Up"; case DOWN -> "Down"; case NONE -> "Stopped"; });
        });
    }

    @Override public void setDoorStatus(int id, DoorStatus s) {
        ui(() -> panel(id).doorText.setText(s == DoorStatus.OPEN ? "Door: Open" : "Door: Closed"));
    }

    @Override public void setObstruction(int id, boolean active) {
        ui(() -> panel(id).obstruction.setFill(active ? Color.ORANGE : Color.WHITE));
    }

    @Override public void setOverload(int id, boolean active) {
        ui(() -> panel(id).overload.setFill(active ? Color.ORANGE : Color.WHITE));
    }

    @Override public void setServiceStatus(int id, ServiceStatus s) {
        ui(() -> { panel(id).status = s; render(id); });
    }

    @Override public void setFireStatus(FireStatus s) {
        ui(() -> {
            fireLabel.setText(switch (s) { case NORMAL -> "Fire: Normal"; case TEST -> "Fire: TEST"; case ALARM -> "Fire: ALARM"; });
            fireLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (s == FireStatus.NORMAL ? "#222" : "#c00") + ";");
        });
    }

    // ---- helpers ----------------------------------------------------------

    private void render(int id) {
        Panel p = panel(id);
        boolean in = p.status == ServiceStatus.IN_SERVICE;
        p.service.setText(in ? "Take out of service" : "Return to service");
        p.title.setText("Elevator " + id + (in ? "" : " (out of service)"));
        p.title.setOpacity(in ? 1 : 0.5);
        p.floor.setOpacity(in ? 1 : 0.5);
    }

    private Panel panel(int id) {
        if (id < 1 || id > ELEVATORS) throw new IllegalArgumentException("elevator " + id);
        return panels[id - 1];
    }

    private static void ui(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    private static StackPane center(javafx.scene.Node n) {
        StackPane sp = new StackPane(n);
        sp.setAlignment(Pos.CENTER);
        return sp;
    }

    private static javafx.scene.layout.HBox row(Circle a, String an, Circle b, String bn) {
        Label la = new Label(an), lb = new Label(bn);
        la.setStyle(CSS_LABEL);
        lb.setStyle(CSS_LABEL);
        javafx.scene.layout.HBox h = new javafx.scene.layout.HBox(4, a, la, b, lb);
        h.setAlignment(Pos.CENTER);
        return h;
    }

    private static Circle lamp() {
        Circle c = new Circle(6, Color.WHITE);
        c.setStroke(Color.BLACK);
        return c;
    }

    private static Polygon arrow(boolean up) {
        Polygon p = up ? new Polygon(0, 22, 26, 22, 13, 0) : new Polygon(0, 0, 26, 0, 13, 22);
        p.setFill(Color.WHITE);
        p.setStroke(Color.BLACK);
        return p;
    }
}
