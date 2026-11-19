package harness;

import commandcenter.CommandCenterApi;
import commandcenter.Direction;
import commandcenter.DoorStatus;
import commandcenter.FireStatus;
import commandcenter.ServiceStatus;

import Utilities.Topics;
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
    // Latest per-elevator telemetry, re-published as catalog CABIN_STATUS / DOOR_STATUS messages.
    private final int[] floor = new int[ELEVATORS + 1], dir = new int[ELEVATORS + 1];
    private final boolean[] doorOpen = new boolean[ELEVATORS + 1], obstructed = new boolean[ELEVATORS + 1],
            overweight = new boolean[ELEVATORS + 1];

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
        System.out.println("Java bus test controller: catalog messages (FIRE, ELEVATOR_COMMAND, CABIN_STATUS, DOOR_STATUS)");
    }

    private void connect() throws IOException {
        Socket next = new Socket();
        socket = next;
        if (stopped) { disconnect(); return; }
        next.connect(new InetSocketAddress(host, port), 2000);
        next.setSoTimeout(2000);
        send(Message.of(Protocol.REGISTER, 0, 0));
        for (int topic : new int[] {Topics.FIRE, Topics.ELEVATOR_COMMAND}) {
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
            for (int topic : new int[] {Topics.FIRE, Topics.ELEVATOR_COMMAND}) {
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
            if (m.flag(1)) test.set(true);
            else reset.set(true);
        } else if (m.topic() == Topics.ELEVATOR_COMMAND) {
            int first = m.subtopic() == Topics.ALL ? 1 : m.subtopic();
            int last = m.subtopic() == Topics.ALL ? ELEVATORS : first;
            if (first < 1 || last > ELEVATORS) return;
            for (int id = first; id <= last; id++) {
                if (!elevatorIds.contains(id)) continue;
                if (m.flag(1)) back[id - 1].set(true);
                else out[id - 1].set(true);
            }
        } else return;
        System.out.println("Java test controller received command " + m);
    }

    private void checkOwned(int id) {
        if (!elevatorIds.contains(id)) {
            throw new IllegalArgumentException("Harness does not own elevator " + id);
        }
    }

    private synchronized void cabinStatus(int id) {
        if (floor[id] < Message.MIN_FLOOR) return; // wait for the first real floor
        statuses.put(new Key(Topics.CABIN_STATUS, id),
                Message.cabinStatus(id, dir[id] == Message.DIR_IDLE, floor[id], dir[id]));
    }

    private synchronized void doorStatus(int id) {
        statuses.put(new Key(Topics.DOOR_STATUS, id),
                Message.doorStatus(id, doorOpen[id], !doorOpen[id], obstructed[id], overweight[id]));
    }

    @Override public boolean fireTest() { return test.getAndSet(false); }
    @Override public boolean fireReset() { return reset.getAndSet(false); }
    @Override public boolean takeOutOfService(int id) { return out[id - 1].getAndSet(false); }
    @Override public boolean returnToService(int id) { return back[id - 1].getAndSet(false); }
    @Override public void setCurrentFloor(int id, int value) {
        checkOwned(id);
        floor[id] = value;
        cabinStatus(id);
    }
    @Override public void setDirection(int id, Direction d) {
        checkOwned(id);
        dir[id] = switch (d) { case NONE -> Message.DIR_IDLE; case UP -> Message.DIR_UP; case DOWN -> Message.DIR_DOWN; };
        cabinStatus(id);
    }
    @Override public void setDoorStatus(int id, DoorStatus s) {
        checkOwned(id);
        doorOpen[id] = s == DoorStatus.OPEN;
        doorStatus(id);
    }
    @Override public void setServiceStatus(int id, ServiceStatus s) {
        checkOwned(id);
        statuses.put(new Key(Topics.ELEVATOR_COMMAND, id),
                Message.elevatorCommand(id, s == ServiceStatus.IN_SERVICE));
    }
    @Override public void setFireStatus(FireStatus s) {
        if (!fireStatusOwner) return;
        statuses.put(new Key(Topics.FIRE, Topics.ALL), Message.fire(s != FireStatus.NORMAL));
    }
    @Override public void setObstruction(int id, boolean active) {
        checkOwned(id);
        obstructed[id] = active;
        doorStatus(id);
    }
    @Override public void setOverload(int id, boolean active) {
        checkOwned(id);
        overweight[id] = active;
        doorStatus(id);
    }

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
