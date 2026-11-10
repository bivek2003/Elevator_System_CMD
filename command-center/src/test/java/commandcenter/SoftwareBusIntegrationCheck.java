package commandcenter;

import harness.BusTestController;

import SoftwareBus.MessageBus;
import SoftwareBus.SoftwareBusServer;
import SoftwareBus.Topics;
import Utilities.Message;
import Utilities.Protocol;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Standalone integration check against the actual upstream bus; no JavaFX toolkit needed. */
public final class SoftwareBusIntegrationCheck {
    private static final class Api implements CommandCenterApi {
        final AtomicBoolean test = new AtomicBoolean(true), reset = new AtomicBoolean();
        final AtomicBoolean out = new AtomicBoolean(), back = new AtomicBoolean();
        volatile int floor;
        volatile Direction direction;
        volatile DoorStatus door;
        volatile FireStatus fire;
        volatile ServiceStatus service;
        public boolean fireTest() { return test.getAndSet(false); }
        public boolean fireReset() { return reset.getAndSet(false); }
        public boolean takeOutOfService(int id) { return id == 2 && out.getAndSet(false); }
        public boolean returnToService(int id) { return id == 2 && back.getAndSet(false); }
        public void setCurrentFloor(int id, int value) { if (id == 2) floor = value; }
        public void setDirection(int id, Direction value) { if (id == 2) direction = value; }
        public void setDoorStatus(int id, DoorStatus value) { if (id == 2) door = value; }
        public void setServiceStatus(int id, ServiceStatus value) { if (id == 2) service = value; }
        public void setFireStatus(FireStatus value) { fire = value; }
        public void setObstruction(int id, boolean value) { }
        public void setOverload(int id, boolean value) { }
    }

    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
        Thread bus = new Thread(() -> {
            try { new SoftwareBusServer(port, new MessageBus()).start(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        bus.setDaemon(true);
        bus.start();
        try (Socket device = connect(port)) {
            device.setSoTimeout(2000);
            send(device, Message.of(Protocol.SUBSCRIBE, Topics.FIRE, Topics.ALL));
            send(device, Message.of(Protocol.SUBSCRIBE, Topics.MODE, 2));
            check(get(device, Topics.FIRE, Topics.ALL).topic() == Protocol.NO_MESSAGE, "empty subscription");
            // Assert the spreadsheet's literal wire values against the shared defaults.
            SoftwareBusManager manager = new SoftwareBusManager("localhost", port);
            Api api = new Api();
            manager.start(api);
            try {
                expect(device, Topics.FIRE, Topics.ALL, 2);
                check(!api.test.get(), "fire test flag consumed");
                check(api.fire == null, "publishing does not fabricate a status echo");
                api.reset.set(true);
                expect(device, Topics.FIRE, Topics.ALL, 0);
                api.out.set(true);
                expect(device, Topics.MODE, 2, 11);
                check(api.service == null, "service waits for controller confirmation");
                send(device, Message.of(Topics.MODE, 2, 11));
                send(device, Message.of(Topics.CAR_POSITION, 2, 4));
                send(device, Message.of(Topics.CAR_DIRECTION, 2, 1));
                send(device, Message.of(Topics.DOOR_STATE, 2, 2));
                send(device, Message.of(Topics.FIRE, Topics.ALL, 1));
                await(() -> api.service == ServiceStatus.OUT_OF_SERVICE && api.floor == 4
                        && api.direction == Direction.UP && api.door == DoorStatus.OPEN
                        && api.fire == FireStatus.ALARM, "received per-car and broadcast statuses");
                api.back.set(true);
                expect(device, Topics.MODE, 2, 10);
                send(device, Message.of(Topics.MODE, 2, 10));
                send(device, Message.of(Topics.FIRE, Topics.ALL, 2));
                await(() -> api.service == ServiceStatus.IN_SERVICE && api.fire == FireStatus.TEST,
                        "configured status codes");
                send(device, Message.of(Topics.CAR_POSITION, 2, Message.MAX_VALUE));
                send(device, Message.of(Topics.CAR_DIRECTION, 2, Message.MAX_VALUE));
                send(device, Message.of(Topics.FIRE, Topics.ALL, 0));
                await(() -> api.fire == FireStatus.NORMAL, "reset status");
                check(api.floor == 4 && api.direction == Direction.UP, "invalid telemetry ignored");
                check(get(device, Topics.FIRE, Topics.ALL).topic() == Protocol.NO_MESSAGE, "no repeated fire commands");
                check(get(device, Topics.MODE, 2).topic() == Protocol.NO_MESSAGE, "no repeated service commands");
            } finally { manager.stop(); }
        }
        checkJavaController(port);
        checkHarnessOwnership(port);
        System.out.println("Software bus integration checks passed (including Java test controller)");
    }

    private static void checkHarnessOwnership(int port) throws Exception {
        try (Socket observer = connect(port);
             BusTestController first = new BusTestController("localhost", port, Set.of(1, 2), true);
             BusTestController second = new BusTestController("localhost", port, Set.of(3, 4), false)) {
            observer.setSoTimeout(2000);
            send(observer, Message.of(Protocol.SUBSCRIBE, Topics.CAR_POSITION, 4));
            send(observer, Message.of(Protocol.SUBSCRIBE, Topics.MODE, 4));
            boolean rejected = false;
            try { first.setCurrentFloor(4, 3); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Test 1 rejects status for Test 2's elevators");
            first.start();
            second.start();
            expect(observer, Topics.CAR_POSITION, 4, 2);
            expect(observer, Topics.MODE, 4, 10);
            send(observer, Message.of(Topics.MODE, 4, 11));
            long deadline = System.nanoTime() + 20_000_000_000L;
            boolean confirmed = false;
            while (System.nanoTime() < deadline && !confirmed) {
                Message status = get(observer, Topics.MODE, 4);
                confirmed = status.topic() == Topics.MODE && status.value() == 11;
                if (!confirmed) Thread.sleep(50);
            }
            check(confirmed, "Test 2 handles elevator 4 service commands with both harnesses running");
            second.close();
            // Test 1 remains live after Test 2 closes.
            send(observer, Message.of(Protocol.SUBSCRIBE, Topics.MODE, 1));
            expect(observer, Topics.MODE, 1, 10);
        }
    }

    private static void checkJavaController(int port) throws Exception {
        Api api = new Api();
        api.test.set(false);
        SoftwareBusManager manager = new SoftwareBusManager("localhost", port,
                BusTestController.IN_SERVICE_CODE, BusTestController.OUT_OF_SERVICE_CODE,
                BusTestController.FIRE_TEST_CODE);
        try (BusTestController controller = new BusTestController("localhost", port)) {
            manager.start(api);
            controller.start();
            try {
                await(() -> api.floor > 0 && api.service == ServiceStatus.IN_SERVICE
                        && api.fire == FireStatus.NORMAL, "Java controller startup telemetry");
                api.test.set(true);
                await(() -> api.fire == FireStatus.TEST, "fire test round trip through bus");
                awaitFor(() -> api.floor == 1 && api.door == DoorStatus.OPEN
                        && api.direction == Direction.NONE, "fire recall parks car at floor 1", 20);
                api.reset.set(true);
                await(() -> api.fire == FireStatus.NORMAL, "fire reset round trip through bus");
                api.out.set(true);
                awaitFor(() -> api.service == ServiceStatus.OUT_OF_SERVICE,
                        "out-of-service confirmed by simulated controller", 20);
                check(api.floor == 1 && api.door == DoorStatus.OPEN, "service confirmation follows recall");
                api.back.set(true);
                await(() -> api.service == ServiceStatus.IN_SERVICE, "return-to-service round trip");
                check(!api.test.get() && !api.reset.get() && !api.out.get() && !api.back.get(),
                        "all operator flags consumed");
            } finally { manager.stop(); }
        }
    }

    private static Socket connect(int port) throws Exception {
        for (int i = 0; ; i++) {
            try { return new Socket("localhost", port); }
            catch (java.io.IOException e) {
                if (i == 50) throw e;
                Thread.sleep(20);
            }
        }
    }

    private static void send(Socket socket, Message message) throws Exception {
        Protocol.writeMessage(socket.getOutputStream(), message);
    }

    private static Message get(Socket socket, int topic, int id) throws Exception {
        send(socket, Message.of(Protocol.GET, topic, id));
        Message result = Protocol.readMessage(socket.getInputStream());
        if (result == null) throw new AssertionError("Bus disconnected");
        return result;
    }

    private static void expect(Socket socket, int topic, int id, long value) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Message result = get(socket, topic, id);
            if (result.topic() != Protocol.NO_MESSAGE) {
                check(result.equals(Message.of(topic, id, value)), "expected message: " + result);
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for published command");
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        awaitFor(condition, description, 5);
    }

    private static void awaitFor(BooleanSupplier condition, String description, int seconds) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
