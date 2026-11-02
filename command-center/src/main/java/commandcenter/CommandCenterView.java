package commandcenter;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;

/**
 * JavaFX Command Center screen: one grid with the fire controls in the top row and one column per
 * elevator below it. Colours live in command-center.css; this class only gives each box and button
 * a {@link Tone}. The API methods are thread-safe.
 */
public class CommandCenterView implements CommandCenterApi {

    /**
     * What a box or button means, which sets its colour. OK is green: moving, or the button that
     * restores normal. CAUTION is amber: a warning, or a command still in progress. ALARM is red.
     * MUTED dims an out-of-service elevator. PLAIN has no colour.
     */
    private enum Tone {
        PLAIN, OK, CAUTION, ALARM, MUTED;

        final PseudoClass pseudoClass = PseudoClass.getPseudoClass(name().toLowerCase(Locale.ROOT));
    }

    /** Set on the arrow of the current direction, and on an obstruction or overload box while active. */
    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");

    private static final double COLUMN_WIDTH = 180;

    /** One elevator: its last reported state and the widgets in its column. */
    private static final class Elevator {
        final int id;
        ServiceStatus service = ServiceStatus.IN_SERVICE;
        int floor;                                   // 0 until the manager reports one
        Direction direction = Direction.NONE;
        DoorStatus door = DoorStatus.CLOSED;
        boolean obstruction, overload;
        boolean pending;                             // service command sent, new status not reported yet
        ServiceStatus pendingTarget;
        final AtomicBoolean takeOutOfService = new AtomicBoolean();
        final AtomicBoolean returnToService = new AtomicBoolean();

        final Label title;
        final Button serviceButton = button("");
        final Polygon upArrow = arrow(true), downArrow = arrow(false);
        final Label floorDisplay = label("", "floor-display");
        final Label directionBox = box(""), spacerBox = box(""), doorBox = box(""),
                obstructionBox = lampBox("Obstruction"), overloadBox = lampBox("Overload");

        Elevator(int id) {
            this.id = id;
            title = label("Elevator " + id, "elevator-title");
        }

        /** The column from top to bottom. */
        Node[] column() {
            return new Node[] {title, serviceButton, upArrow, floorDisplay, downArrow,
                    directionBox, spacerBox, doorBox, obstructionBox, overloadBox};
        }
    }

    private final GridPane root = new GridPane();
    private final Button fireReset = button("Fire Reset"), fireTest = button("Fire Test");
    private final Label fireAlarm = box("Fire Alarm");
    private final Elevator[] elevators = new Elevator[ELEVATORS];
    private FireStatus fire = FireStatus.NORMAL;
    private final AtomicBoolean fireResetPending = new AtomicBoolean();
    private final AtomicBoolean fireTestPending = new AtomicBoolean();

    public CommandCenterView() {
        root.getStyleClass().add("command-center");
        root.getStylesheets().add(Objects.requireNonNull(
                CommandCenterView.class.getResource("command-center.css"), "command-center.css is missing")
                .toExternalForm());
        for (int c = 0; c < ELEVATORS; c++) {
            ColumnConstraints column = new ColumnConstraints(COLUMN_WIDTH);
            column.setHalignment(HPos.CENTER);
            root.getColumnConstraints().add(column);
        }

        // Row 0: fire controls, one box per column
        fireReset.setOnAction(ev -> fireResetPending.set(true));
        fireTest.setOnAction(ev -> fireTestPending.set(true));
        root.addRow(0, fireReset, fireTest, box(""), fireAlarm);

        // Rows 1..10: one column per elevator, the same rows in every column
        for (int i = 0; i < ELEVATORS; i++) {
            Elevator e = elevators[i] = new Elevator(i + 1);
            e.serviceButton.setOnAction(ev -> onServiceButton(e));
            GridPane.setMargin(e.title, new Insets(18, 0, 0, 0));   // space below the fire row
            GridPane.setMargin(e.upArrow, new Insets(6, 0, 0, 0));
            GridPane.setMargin(e.directionBox, new Insets(6, 0, 0, 0));
            Node[] column = e.column();
            for (int r = 0; r < column.length; r++) {
                root.add(column[r], i, r + 1);
            }
        }

        renderFire();
        for (Elevator e : elevators) render(e);
    }

    public Parent getRoot() { return root; }

    // ---- CommandCenterApi -------------------------------------------------

    @Override public boolean fireReset() { return fireResetPending.getAndSet(false); }

    @Override public boolean fireTest() { return fireTestPending.getAndSet(false); }

    @Override public boolean takeOutOfService(int id) {
        return elevator(id).takeOutOfService.getAndSet(false);
    }

    @Override public boolean returnToService(int id) {
        return elevator(id).returnToService.getAndSet(false);
    }

    @Override public void setCurrentFloor(int id, int floor) {
        Elevator e = elevator(id);
        ui(() -> { e.floor = floor; render(e); });
    }

    @Override public void setDirection(int id, Direction d) {
        Elevator e = elevator(id);
        Objects.requireNonNull(d);
        ui(() -> { e.direction = d; render(e); });
    }

    @Override public void setDoorStatus(int id, DoorStatus s) {
        Elevator e = elevator(id);
        Objects.requireNonNull(s);
        ui(() -> { e.door = s; render(e); });
    }

    @Override public void setObstruction(int id, boolean active) {
        Elevator e = elevator(id);
        ui(() -> { e.obstruction = active; render(e); });
    }

    @Override public void setOverload(int id, boolean active) {
        Elevator e = elevator(id);
        ui(() -> { e.overload = active; render(e); });
    }

    /** Ends a pending service command only when its requested state is confirmed. */
    @Override public void setServiceStatus(int id, ServiceStatus s) {
        Elevator e = elevator(id);
        Objects.requireNonNull(s);
        ui(() -> {
            e.service = s;
            if (s == e.pendingTarget) { e.pending = false; e.pendingTarget = null; }
            render(e);
        });
    }

    @Override public void setFireStatus(FireStatus s) {
        Objects.requireNonNull(s);
        ui(() -> { fire = s; renderFire(); });
    }

    // ---- behaviour ----------------------------------------------------------

    /** Asks the manager for the opposite service state; the button stays lit amber until it reports back. */
    private void onServiceButton(Elevator e) {
        if (e.pending) return;
        e.pending = true;
        e.pendingTarget = e.service == ServiceStatus.IN_SERVICE
                ? ServiceStatus.OUT_OF_SERVICE : ServiceStatus.IN_SERVICE;
        render(e);
        if (e.service == ServiceStatus.IN_SERVICE) e.takeOutOfService.set(true);
        else e.returnToService.set(true);
    }

    private static void render(Elevator e) {
        boolean out = e.service == ServiceStatus.OUT_OF_SERVICE;
        Tone dim = out ? Tone.MUTED : Tone.PLAIN;   // an out-of-service column is dimmed

        if (e.pending) {
            show(e.serviceButton, out ? "Returning to service…" : "Taking out of service…", Tone.CAUTION);
        } else {
            show(e.serviceButton, out ? "Return to service" : "Take out of service", out ? Tone.OK : Tone.PLAIN);
        }
        e.serviceButton.setDisable(e.pending);

        e.upArrow.pseudoClassStateChanged(ON, e.direction == Direction.UP);
        e.downArrow.pseudoClassStateChanged(ON, e.direction == Direction.DOWN);
        setTone(e.upArrow, dim);
        setTone(e.downArrow, dim);
        show(e.floorDisplay, e.floor > 0 ? Integer.toString(e.floor) : "–", dim);

        show(e.directionBox, switch (e.direction) {
            case UP -> "Up";
            case DOWN -> "Down";
            case NONE -> "Stopped";
        }, out ? Tone.MUTED : e.direction == Direction.NONE ? Tone.PLAIN : Tone.OK);
        setTone(e.spacerBox, dim);
        show(e.doorBox, e.door == DoorStatus.OPEN ? "Door: Open" : "Door: Closed", dim);
        showLamp(e.obstructionBox, e.obstruction, out);
        showLamp(e.overloadBox, e.overload, out);
    }

    private void renderFire() {
        setTone(fireAlarm, switch (fire) {
            case NORMAL -> Tone.PLAIN;
            case TEST -> Tone.CAUTION;
            case ALARM -> Tone.ALARM;
        });
        setTone(fireTest, fire == FireStatus.TEST ? Tone.CAUTION : Tone.PLAIN);     // lit while the test runs
        setTone(fireReset, fire == FireStatus.NORMAL ? Tone.PLAIN : Tone.OK);       // lit when there is something to reset
    }

    // ---- helpers ------------------------------------------------------------

    private Elevator elevator(int id) {
        if (id < 1 || id > ELEVATORS) throw new IllegalArgumentException("elevator " + id);
        return elevators[id - 1];
    }

    private static void ui(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    private static void show(Labeled node, String text, Tone tone) {
        node.setText(text);
        setTone(node, tone);
    }

    private static void showLamp(Label box, boolean on, boolean muted) {
        box.pseudoClassStateChanged(ON, on);
        setTone(box, muted ? Tone.MUTED : on ? Tone.CAUTION : Tone.PLAIN);
    }

    private static void setTone(Node node, Tone tone) {
        for (Tone t : Tone.values()) node.pseudoClassStateChanged(t.pseudoClass, t == tone);
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Label box(String text) {
        Label box = label(text, "box");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static Label lampBox(String text) {
        Circle lamp = new Circle(7);
        lamp.getStyleClass().add("lamp");
        Label box = box(text);
        box.setGraphic(lamp);
        return box;
    }

    private static Polygon arrow(boolean up) {
        Polygon p = up ? new Polygon(0, 26, 42, 26, 21, 0) : new Polygon(0, 0, 42, 0, 21, 26);
        p.getStyleClass().add("dir-arrow");
        return p;
    }

    private static Button button(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("cc-button");
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }
}
