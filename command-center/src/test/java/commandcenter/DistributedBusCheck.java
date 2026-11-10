package commandcenter;

import integration.BusConnection;
import java.net.ServerSocket;
import SoftwareBus.Topics;
import Utilities.Message;
import Utilities.Protocol;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BooleanSupplier;

/** Panel-side bus/manager without JavaFX, with Test1 and Test2 in separate JVMs. */
public class DistributedBusCheck {
    private static class PanelState implements CommandCenterApi {
        final AtomicIntegerArray floors = new AtomicIntegerArray(5);
        final AtomicIntegerArray updates = new AtomicIntegerArray(5);
        final AtomicBoolean test = new AtomicBoolean(), reset = new AtomicBoolean();
        final AtomicBoolean out = new AtomicBoolean(), back = new AtomicBoolean();
        volatile FireStatus fire;
        volatile ServiceStatus service4;
        volatile DoorStatus door4;
        public void setCurrentFloor(int id, int floor) { floors.set(id, floor); updates.incrementAndGet(id); }
        public void setDirection(int id, Direction direction) { }
        public void setDoorStatus(int id, DoorStatus door) { if (id == 4) door4 = door; }
        public void setObstruction(int id, boolean active) { }
        public void setOverload(int id, boolean active) { }
        public void setServiceStatus(int id, ServiceStatus service) { if (id == 4) service4 = service; }
        public void setFireStatus(FireStatus status) { fire = status; }
        public boolean fireTest() { return test.getAndSet(false); }
        public boolean fireReset() { return reset.getAndSet(false); }
        public boolean takeOutOfService(int id) { return id == 4 && out.getAndSet(false); }
        public boolean returnToService(int id) { return id == 4 && back.getAndSet(false); }
    }

    public static void main(String[] args) {
        try {
            runChecks();
        } catch (Exception | AssertionError e) {
            e.printStackTrace();
            System.exit(1);
        }
        // The unchanged upstream bus has no stop API.
        System.exit(0);
    }

    private static void runChecks() throws Exception {
        Process router = null, test1 = null, test2 = null;
        SoftwareBusManager manager = null;
        Path logs = Files.createTempDirectory("distributed-bus-check-");
        System.out.println("Harness logs: " + logs);
        int routerPort = freePort();
        int panelPort = freePort();
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            router = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    "VirtualRouter.VRMain", Integer.toString(routerPort))
                    .redirectErrorStream(true).redirectOutput(logs.resolve("router.log").toFile()).start();
            await(() -> portReady(routerPort), "upstream Virtual Router ready");
            BusConnection.open(panelPort, new String[] {"localhost", Integer.toString(routerPort)});
            PanelState panel = new PanelState();
            manager = new SoftwareBusManager("localhost", panelPort);
            manager.start(panel);
            test1 = startHarness("Test1", routerPort, logs.resolve("test1.log"));
            test2 = startHarness("Test2", routerPort, logs.resolve("test2.log"));
            await(() -> panel.floors.get(1) > 0 && panel.floors.get(2) > 0
                    && panel.floors.get(3) > 0 && panel.floors.get(4) > 0, "status from both remote buses");
            checkUnsubscribe(panelPort);
            panel.test.set(true);
            await(() -> panel.fire == FireStatus.TEST, "fire test across buses");
            panel.reset.set(true);
            await(() -> panel.fire == FireStatus.NORMAL, "fire reset across buses");
            panel.out.set(true);
            await(() -> panel.service4 == ServiceStatus.OUT_OF_SERVICE, "Test2 service acknowledgment");
            if (panel.floors.get(4) != 1 || panel.door4 != DoorStatus.OPEN) {
                throw new AssertionError("Service acknowledgment arrived before recall completed");
            }
            panel.back.set(true);
            await(() -> panel.service4 == ServiceStatus.IN_SERVICE, "Test2 return to service");
            stopHarness(test2);
            int before = panel.updates.get(1);
            await(() -> panel.updates.get(1) > before, "Test1 continues after Test2 stops");
            int previous = panel.updates.get(4);
            test2 = startHarness("Test2", routerPort, logs.resolve("test2-restart.log"));
            await(() -> panel.updates.get(4) > previous, "Test2 reconnects after restarting");
            System.out.println("Distributed bus checks passed (three subsystem processes plus upstream router)");
        } finally {
            if (manager != null) manager.stop();
            stopHarness(test1);
            stopHarness(test2);
            stopHarness(router);
        }
    }

    private static Process startHarness(String name, int peerPort, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "harness." + name, "localhost", Integer.toString(peerPort), Integer.toString(freePort()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static boolean portReady(int port) {
        try (Socket socket = new Socket("localhost", port)) { return true; }
        catch (java.io.IOException e) { return false; }
    }

    private static void stopHarness(Process process) throws Exception {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Harness did not stop");
        }
    }

    private static void checkUnsubscribe(int port) throws Exception {
        try (Socket observer = new Socket("localhost", port)) {
            observer.setSoTimeout(2000);
            Protocol.writeMessage(observer.getOutputStream(), Message.of(Protocol.REGISTER, 0, 0));
            Protocol.writeMessage(observer.getOutputStream(), Message.of(Protocol.SUBSCRIBE, Topics.CAR_POSITION, 4));
            await(() -> {
                try { return getPosition(observer).topic() == Topics.CAR_POSITION; }
                catch (Exception e) { throw new RuntimeException(e); }
            }, "subscriber receives remote Test2 status");
            Protocol.writeMessage(observer.getOutputStream(), Message.of(Protocol.UNSUBSCRIBE, Topics.CAR_POSITION, 4));
            if (getPosition(observer).topic() != Protocol.NO_MESSAGE) {
                throw new AssertionError("Unsubscribed queue still delivers messages");
            }
        }
    }

    private static Message getPosition(Socket socket) throws Exception {
        Protocol.writeMessage(socket.getOutputStream(), Message.of(Protocol.GET, Topics.CAR_POSITION, 4));
        Message result = Protocol.readMessage(socket.getInputStream());
        if (result == null) throw new AssertionError("Observer disconnected");
        return result;
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out: " + description);
            Thread.sleep(50);
        }
        System.out.println("PASS: " + description);
    }
}
