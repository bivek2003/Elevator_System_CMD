package commandcenter;

import harness.BusTestController;

import SoftwareBus.MessageBus;
import SoftwareBus.SoftwareBusServer;
import Utilities.Topics;
import Utilities.Message;
import Utilities.Protocol;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        volatile boolean obstructed, overweight;
        volatile boolean cabinFresh, doorFresh, serviceFresh, fireFresh;
        final AtomicInteger cabinUpdates = new AtomicInteger();
        public boolean fireTest() { return test.getAndSet(false); }
        public boolean fireReset() { return reset.getAndSet(false); }
        public boolean takeOutOfService(int id) { return id == 2 && out.getAndSet(false); }
        public boolean returnToService(int id) { return id == 2 && back.getAndSet(false); }
        public void setCurrentFloor(int id, int value) { if (id == 2) { floor = value; cabinUpdates.incrementAndGet(); } }
        public void setDirection(int id, Direction value) { if (id == 2) direction = value; }
        public void setDoorStatus(int id, DoorStatus value) { if (id == 2) door = value; }
        public void setServiceStatus(int id, ServiceStatus value) { if (id == 2) service = value; }
        public void setFireStatus(FireStatus value) { fire = value; }
        public void setObstruction(int id, boolean value) { if (id == 2) obstructed = value; }
        public void setOverload(int id, boolean value) { if (id == 2) overweight = value; }
        public void setCabinStatusFresh(int id, boolean value) { if (id == 2) cabinFresh = value; }
        public void setDoorStatusFresh(int id, boolean value) { if (id == 2) doorFresh = value; }
        public void setServiceStatusFresh(int id, boolean value) { if (id == 2) serviceFresh = value; }
        public void setFireStatusFresh(boolean value) { fireFresh = value; }
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
        checkWildcard(port);
        try (Socket device = connect(port)) {
            device.setSoTimeout(2000);
            int id = register(device);
            check(register(device) == id, "repeated registration keeps the connection ID");
            send(device, Message.of(Protocol.SUBSCRIBE, Topics.FIRE, Topics.ALL));
            send(device, Message.of(Protocol.SUBSCRIBE, Topics.ELEVATOR_COMMAND, 2));
            check(get(device, Topics.FIRE, Topics.ALL).topic() == Protocol.NO_MESSAGE, "empty subscription");
            SoftwareBusManager manager = new SoftwareBusManager("localhost", port);
            Api api = new Api();
            manager.start(api);
            try {
                expect(device, Message.fire(true));
                check(!api.test.get(), "fire test flag consumed");
                check(api.fire == null, "publishing does not fabricate a status echo");
                api.reset.set(true);
                expect(device, Message.fire(false));
                api.out.set(true);
                expect(device, Message.elevatorCommand(2, false));
                check(api.service == null, "service waits for controller confirmation");
                send(device, Message.elevatorCommand(2, false));
                send(device, Message.cabinStatus(2, false, 4, Message.DIR_UP));
                send(device, Message.doorStatus(2, true, false, true, false));
                send(device, Message.fire(true));
                await(() -> api.service == ServiceStatus.OUT_OF_SERVICE && api.floor == 4
                        && api.direction == Direction.UP && api.door == DoorStatus.OPEN
                        && api.obstructed && !api.overweight
                        && api.fire == FireStatus.ALARM && api.cabinFresh && api.doorFresh
                        && api.serviceFresh && api.fireFresh, "received per-car and broadcast statuses");
                Thread.sleep(300);
                check(api.cabinUpdates.get() == 1, "wildcard polling applies each cabin update once");
                send(device, Message.doorStatus(2, false, false, true, true));
                await(() -> api.door == DoorStatus.TRANSITION && api.overweight,
                        "neither door limit means transition, not closed");
                send(device, Message.doorStatus(2, true, true, false, false));
                Thread.sleep(300);
                check(api.door == DoorStatus.TRANSITION && api.overweight,
                        "contradictory door limits do not fabricate a closed door or clear warnings");
                send(device, Message.doorStatus(2, false, true, false, false));
                await(() -> api.door == DoorStatus.CLOSED && !api.obstructed && !api.overweight,
                        "door warnings clear with reported normal status");
                api.back.set(true);
                expect(device, Message.elevatorCommand(2, true));
                send(device, Message.elevatorCommand(2, true));
                send(device, Message.fire(false));
                await(() -> api.service == ServiceStatus.IN_SERVICE && api.fire == FireStatus.NORMAL,
                        "service and reset statuses");
                // Out-of-range telemetry is rejected by the builders and ignored by the manager.
                boolean rejected = false;
                try { Message.cabinStatus(2, false, 11, Message.DIR_UP); }
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "builder rejects floor 11");
                send(device, Message.ofPayload(Topics.CABIN_STATUS, 2, 0, 99, 7));
                send(device, Message.cabinStatus(2, true, 6, Message.DIR_IDLE));
                send(device, Message.ofPayload(Topics.FIRE, Topics.NONE, 2));
                send(device, Message.ofPayload(Topics.ELEVATOR_COMMAND, 2, 2));
                Thread.sleep(500);
                check(api.floor == 4 && api.direction == Direction.UP, "invalid telemetry ignored");
                check(api.fire == FireStatus.NORMAL && api.service == ServiceStatus.IN_SERVICE,
                        "invalid fire and service flags ignored");
                check(get(device, Topics.FIRE, Topics.ALL).topic() == Protocol.NO_MESSAGE, "no repeated fire commands");
                check(get(device, Topics.ELEVATOR_COMMAND, 2).topic() == Protocol.NO_MESSAGE, "no repeated service commands");
                awaitFor(() -> !api.cabinFresh && !api.doorFresh && !api.serviceFresh && !api.fireFresh,
                        "silent status sources become stale", 7);
            } finally { manager.stop(); }
        }
        checkJavaController(port);
        checkHarnessOwnership(port);
        checkReconnect();
        System.out.println("Software bus integration checks passed (including Java test controller)");
    }

    private static void checkWildcard(int port) throws Exception {
        try (Socket receiver = connect(port); Socket sender = connect(port)) {
            receiver.setSoTimeout(2000);
            sender.setSoTimeout(2000);
            check(register(receiver) != register(sender), "live connections get distinct IDs");
            send(receiver, Message.of(Protocol.SUBSCRIBE, Topics.CABIN_STATUS, Topics.ALL));
            check(get(receiver, Topics.CABIN_STATUS, Topics.ALL).topic() == Protocol.NO_MESSAGE,
                    "wildcard subscription starts empty");
            Message first = Message.cabinStatus(1, true, 1, Message.DIR_IDLE);
            Message last = Message.cabinStatus(4, false, 5, Message.DIR_DOWN);
            send(sender, first);
            send(sender, last);
            // A GET on the publishing socket is a barrier: both sends have been processed.
            get(sender, Topics.CABIN_STATUS, Topics.ALL);
            check(get(receiver, Topics.CABIN_STATUS, Topics.ALL).equals(first), "wildcard receives elevator 1");
            check(get(receiver, Topics.CABIN_STATUS, Topics.ALL).equals(last), "wildcard receives elevator 4");
            check(get(receiver, Topics.CABIN_STATUS, Topics.ALL).topic() == Protocol.NO_MESSAGE,
                    "wildcard queue has no duplicate frames");
            send(receiver, Message.of(Protocol.UNSUBSCRIBE, Topics.CABIN_STATUS, Topics.ALL));
            get(receiver, Topics.CABIN_STATUS, Topics.ALL);
            send(sender, first);
            get(sender, Topics.CABIN_STATUS, Topics.ALL);
            check(get(receiver, Topics.CABIN_STATUS, Topics.ALL).topic() == Protocol.NO_MESSAGE,
                    "unsubscribed wildcard receives nothing");
        }
    }

    private static void checkReconnect() throws Exception {
        int port;
        try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
        Path logs = Files.createTempDirectory("bus-reconnect-check-");
        Process bus = startBus(port, logs.resolve("first.log"));
        Api api = new Api();
        api.test.set(false);
        SoftwareBusManager manager = new SoftwareBusManager("localhost", port);
        try (BusTestController controller = new BusTestController("localhost", port)) {
            manager.start(api);
            controller.start();
            try {
                await(() -> api.cabinFresh && api.fireFresh, "initial connection before bus restart");
                stopBus(bus);
                await(() -> !api.cabinFresh && !api.fireFresh, "lost client connection marks status stale");
                bus = startBus(port, logs.resolve("restart.log"));
                await(() -> api.cabinFresh && api.serviceFresh && api.fireFresh,
                        "manager and harness register and restore subscriptions after reconnect");
                api.test.set(true);
                await(() -> api.fire == FireStatus.ALARM, "fire command works after bus reconnect");
                api.out.set(true);
                awaitFor(() -> api.service == ServiceStatus.OUT_OF_SERVICE,
                        "service command works after bus reconnect", 20);
            } finally { manager.stop(); }
        } finally { stopBus(bus); }
        System.out.println("Client reconnect checks passed; logs: " + logs);
    }

    private static Process startBus(int port, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "SoftwareBus.SBMain", Integer.toString(port))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void stopBus(Process bus) throws Exception {
        if (bus == null || !bus.isAlive()) return;
        bus.destroy();
        if (!bus.waitFor(5, TimeUnit.SECONDS)) {
            bus.destroyForcibly();
            bus.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static void checkHarnessOwnership(int port) throws Exception {
        try (Socket observer = connect(port);
             BusTestController first = new BusTestController("localhost", port, Set.of(1, 2), true);
             BusTestController second = new BusTestController("localhost", port, Set.of(3, 4), false)) {
            observer.setSoTimeout(2000);
            register(observer);
            send(observer, Message.of(Protocol.SUBSCRIBE, Topics.CABIN_STATUS, 4));
            send(observer, Message.of(Protocol.SUBSCRIBE, Topics.ELEVATOR_COMMAND, 4));
            boolean rejected = false;
            try { first.setCurrentFloor(4, 3); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Test 1 rejects status for Test 2's elevators");
            first.start();
            second.start();
            expectAny(observer, Topics.CABIN_STATUS, 4);
            expect(observer, Message.elevatorCommand(4, true));
            send(observer, Message.elevatorCommand(4, false));
            long deadline = System.nanoTime() + 20_000_000_000L;
            boolean confirmed = false;
            while (System.nanoTime() < deadline && !confirmed) {
                Message status = get(observer, Topics.ELEVATOR_COMMAND, 4);
                confirmed = status.topic() == Topics.ELEVATOR_COMMAND && !status.flag(1);
                if (!confirmed) Thread.sleep(50);
            }
            check(confirmed, "Test 2 handles elevator 4 service commands with both harnesses running");
            second.close();
            // Test 1 remains live after Test 2 closes.
            send(observer, Message.of(Protocol.SUBSCRIBE, Topics.ELEVATOR_COMMAND, 1));
            expect(observer, Message.elevatorCommand(1, true));
        }
    }

    private static void checkJavaController(int port) throws Exception {
        Api api = new Api();
        api.test.set(false);
        SoftwareBusManager manager = new SoftwareBusManager("localhost", port);
        try (BusTestController controller = new BusTestController("localhost", port)) {
            manager.start(api);
            controller.start();
            try {
                await(() -> api.floor > 0 && api.service == ServiceStatus.IN_SERVICE
                        && api.fire == FireStatus.NORMAL, "Java controller startup telemetry");
                api.test.set(true);
                await(() -> api.fire == FireStatus.ALARM, "fire test round trip through bus");
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

    private static int register(Socket socket) throws Exception {
        send(socket, Message.of(Protocol.REGISTER, 0, 0));
        Message reply = Protocol.readMessage(socket.getInputStream());
        check(reply != null && reply.topic() == Protocol.REGISTER && reply.subtopic() != 0,
                "registration reply has a valid assigned ID");
        return reply.subtopic();
    }

    private static Message get(Socket socket, int topic, int id) throws Exception {
        send(socket, Message.of(Protocol.GET, topic, id));
        Message result = Protocol.readMessage(socket.getInputStream());
        if (result == null) throw new AssertionError("Bus disconnected");
        return result;
    }

    private static void expectAny(Socket socket, int topic, int id) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (get(socket, topic, id).topic() != Protocol.NO_MESSAGE) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for " + Topics.topicName(topic));
    }

    private static void expect(Socket socket, Message expected) throws Exception {
        int topic = expected.topic(), id = expected.subtopic();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Message result = get(socket, topic, id);
            if (result.topic() != Protocol.NO_MESSAGE) {
                check(result.equals(expected), "expected " + expected + " but got " + result);
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
