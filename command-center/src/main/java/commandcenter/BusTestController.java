package commandcenter;

import SoftwareBus.Topics;
import Utilities.Message;
import Utilities.Protocol;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java test device: TestManager talks to this adapter, never directly to the view. */
public final class BusTestController implements CommandCenterApi, AutoCloseable {
    // Temporary classroom codes, shared by both ends of --bus-demo, not team standards.
    public static final long IN_SERVICE_CODE = 10;
    public static final long OUT_OF_SERVICE_CODE = 11;
    public static final long FIRE_TEST_CODE = 2;

    private record Key(int topic, int elevator) { }
    private final String host;
    private final int port;
    private final TestManager simulation = new TestManager(false);
    private final AtomicBoolean test = new AtomicBoolean(), reset = new AtomicBoolean();
    private final AtomicBoolean[] out = flags(), back = flags();
    private final Map<Key, Message> statuses = new ConcurrentHashMap<>();
    private final Map<Key, Message> changed = new ConcurrentHashMap<>();
    private volatile Socket socket;
    private volatile boolean stopped;
    private ScheduledExecutorService worker;
    private long nextSnapshot;

    public BusTestController(String host, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid bus port");
        this.host = host;
        this.port = port;
    }

    public synchronized void start() {
        if (worker != null || stopped) throw new IllegalStateException("Controller already started or closed");
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bus-test-controller");
            t.setDaemon(true);
            return t;
        });
        simulation.start(this);
        worker.scheduleWithFixedDelay(this::poll, 0, 100, TimeUnit.MILLISECONDS);
        System.out.println("Java bus test controller: demo MODE 10=in service, 11=out; FIRE 2=test");
    }

    private void connect() throws IOException {
        Socket next = new Socket();
        socket = next;
        if (stopped) { disconnect(); return; }
        next.connect(new InetSocketAddress(host, port), 2000);
        next.setSoTimeout(2000);
        for (int topic : new int[] {Topics.FIRE, Topics.MODE}) {
            for (int id = 0; id <= ELEVATORS; id++) send(Message.of(Protocol.SUBSCRIBE, topic, id));
        }
        nextSnapshot = 0;
        System.out.println("Java test controller connected to bus at " + host + ":" + port);
    }

    private void poll() {
        if (stopped) return;
        try {
            if (socket == null) connect();
            if (stopped) return;
            for (int topic : new int[] {Topics.FIRE, Topics.MODE}) {
                for (int id = 0; id <= ELEVATORS; id++) {
                    for (int n = 0; n < 32; n++) {
                        send(Message.of(Protocol.GET, topic, id));
                        Message m = Protocol.readMessage(socket.getInputStream());
                        if (m == null) throw new EOFException("Bus disconnected");
                        if (m.topic() == Protocol.NO_MESSAGE) break;
                        if (m.subtopic() != Topics.ALL || id == Topics.ALL) command(m);
                    }
                }
            }
            // Resend a snapshot every second so devices can start in either order.
            // Both maps coalesce telemetry by topic/elevator during a disconnect.
            if (System.nanoTime() >= nextSnapshot) {
                statuses.forEach(changed::putIfAbsent);
                nextSnapshot = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            }
            for (var entry : changed.entrySet()) {
                send(entry.getValue());
                changed.remove(entry.getKey(), entry.getValue());
            }
        } catch (IOException | RuntimeException e) {
            disconnect();
            if (!stopped) System.err.println("Java test controller retrying: " + e.getMessage());
        }
    }

    private void command(Message m) {
        if (m.topic() == Topics.FIRE) {
            if (m.value() == 0) reset.set(true);
            else if (m.value() == FIRE_TEST_CODE || m.value() == 1) test.set(true);
            else return;
        } else if (m.topic() == Topics.MODE) {
            int first = m.subtopic() == Topics.ALL ? 1 : m.subtopic();
            int last = m.subtopic() == Topics.ALL ? ELEVATORS : first;
            if (first < 1 || last > ELEVATORS) return;
            for (int id = first; id <= last; id++) {
                if (m.value() == IN_SERVICE_CODE) back[id - 1].set(true);
                else if (m.value() == OUT_OF_SERVICE_CODE) out[id - 1].set(true);
                else return;
            }
        } else return;
        System.out.println("Java test controller received command " + m);
    }

    private void status(int topic, int id, long value) {
        Key key = new Key(topic, id);
        Message m = Message.of(topic, id, value);
        statuses.put(key, m);
        changed.put(key, m);
    }

    @Override public boolean fireTest() { return test.getAndSet(false); }
    @Override public boolean fireReset() { return reset.getAndSet(false); }
    @Override public boolean takeOutOfService(int id) { return out[id - 1].getAndSet(false); }
    @Override public boolean returnToService(int id) { return back[id - 1].getAndSet(false); }
    @Override public void setCurrentFloor(int id, int floor) { status(Topics.CAR_POSITION, id, floor); }
    @Override public void setDirection(int id, Direction d) {
        status(Topics.CAR_DIRECTION, id, switch (d) { case NONE -> 0; case UP -> 1; case DOWN -> 2; });
    }
    @Override public void setDoorStatus(int id, DoorStatus s) {
        status(Topics.DOOR_STATE, id, s == DoorStatus.OPEN ? 2 : 0);
    }
    @Override public void setServiceStatus(int id, ServiceStatus s) {
        status(Topics.MODE, id, s == ServiceStatus.IN_SERVICE ? IN_SERVICE_CODE : OUT_OF_SERVICE_CODE);
    }
    @Override public void setFireStatus(FireStatus s) {
        status(Topics.FIRE, Topics.ALL, switch (s) { case NORMAL -> 0; case ALARM -> 1; case TEST -> FIRE_TEST_CODE; });
    }
    // No upstream topics exist for these warnings; bus demos disable random faults.
    @Override public void setObstruction(int id, boolean active) { }
    @Override public void setOverload(int id, boolean active) { }

    private void send(Message m) throws IOException {
        Protocol.writeMessage(socket.getOutputStream(), m);
    }

    private void disconnect() {
        Socket old = socket;
        socket = null;
        if (old != null) try { old.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        stopped = true;
        simulation.stop();
        disconnect();
        if (worker != null) worker.shutdownNow();
    }

    private static AtomicBoolean[] flags() {
        AtomicBoolean[] result = new AtomicBoolean[ELEVATORS];
        for (int i = 0; i < result.length; i++) result[i] = new AtomicBoolean();
        return result;
    }

    /** Separate-terminal option, using the same temporary codes as --bus-demo. */
    public static void main(String[] args) throws InterruptedException {
        if (args.length != 0 && args.length != 2) {
            throw new IllegalArgumentException("Usage: BusTestController [host port]");
        }
        BusTestController controller = new BusTestController(args.length == 0 ? "localhost" : args[0],
                args.length == 0 ? 6000 : Integer.parseInt(args[1]));
        Runtime.getRuntime().addShutdownHook(new Thread(controller::close));
        controller.start();
        new CountDownLatch(1).await();
    }
}
