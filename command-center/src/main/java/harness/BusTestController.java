package harness;

import commandcenter.BusCodes;
import commandcenter.CommandCenterApi;
import commandcenter.Direction;
import commandcenter.DoorStatus;
import commandcenter.FireStatus;
import commandcenter.ServiceStatus;

import SoftwareBus.Topics;
import Utilities.Message;
import Utilities.Protocol;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Java test device: TestManager talks to this adapter, never directly to the view. */
public final class BusTestController implements CommandCenterApi, AutoCloseable {
    // Shared with local and remote UI connections, per Software_Bus_Codes.xlsx.
    public static final long IN_SERVICE_CODE = BusCodes.IN_SERVICE;
    public static final long OUT_OF_SERVICE_CODE = BusCodes.OUT_OF_SERVICE;
    public static final long FIRE_TEST_CODE = BusCodes.FIRE_TEST;

    private record Key(int topic, int elevator) { }
    private final String host;
    private final int port;
    private final TestManager simulation;
    private final Set<Integer> elevatorIds;
    private final int[] subscriptions;
    private final boolean fireStatusOwner;
    private final AtomicBoolean test = new AtomicBoolean(), reset = new AtomicBoolean();
    private final AtomicBoolean[] out = flags(), back = flags();
    private final Map<Key, Message> statuses = new ConcurrentHashMap<>();
    private volatile Socket socket;
    private volatile boolean stopped;
    private ScheduledExecutorService worker;
    private long nextSnapshot;

    public BusTestController(String host, int port) {
        this(host, port, Set.of(1, 2, 3, 4), true);
    }

    public BusTestController(String host, int port, Set<Integer> elevatorIds, boolean fireStatusOwner) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid bus port");
        this.host = host;
        this.port = port;
        this.elevatorIds = Set.copyOf(elevatorIds);
        this.simulation = new TestManager(false, this.elevatorIds);
        this.subscriptions = new int[elevatorIds.size() + 1];
        subscriptions[0] = Topics.ALL;
        int index = 1;
        for (int id = 1; id <= ELEVATORS; id++) {
            if (this.elevatorIds.contains(id)) subscriptions[index++] = id;
        }
        this.fireStatusOwner = fireStatusOwner;
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
        System.out.println("Java bus test controller: MODE 10=in service, 11=out; FIRE 2=test");
    }

    private void connect() throws IOException {
        Socket next = new Socket();
        socket = next;
        if (stopped) { disconnect(); return; }
        next.connect(new InetSocketAddress(host, port), 2000);
        next.setSoTimeout(2000);
        send(Message.of(Protocol.REGISTER, 0, 0));
        for (int topic : new int[] {Topics.FIRE, Topics.MODE}) {
            for (int id : subscriptions) send(Message.of(Protocol.SUBSCRIBE, topic, id));
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
                for (int id : subscriptions) {
                    for (int n = 0; n < 32; n++) {
                        send(Message.of(Protocol.GET, topic, id));
                        Message m = Protocol.readMessage(socket.getInputStream());
                        if (m == null) throw new EOFException("Bus disconnected");
                        if (m.topic() == Protocol.NO_MESSAGE) break;
                        if (m.subtopic() != Topics.ALL || id == Topics.ALL) command(m);
                    }
                }
            }
            // Send the latest status once a second, including after a reconnect.
            if (System.nanoTime() >= nextSnapshot) {
                for (Message status : statuses.values()) send(status);
                nextSnapshot = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
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
                if (!elevatorIds.contains(id)) continue;
                if (m.value() == IN_SERVICE_CODE) back[id - 1].set(true);
                else if (m.value() == OUT_OF_SERVICE_CODE) out[id - 1].set(true);
                else return;
            }
        } else return;
        System.out.println("Java test controller received command " + m);
    }

    private void status(int topic, int id, long value) {
        if (id != Topics.ALL && !elevatorIds.contains(id)) {
            throw new IllegalArgumentException("Harness does not own elevator " + id);
        }
        Key key = new Key(topic, id);
        Message m = Message.of(topic, id, value);
        statuses.put(key, m);
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
        if (!fireStatusOwner) return;
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

}
