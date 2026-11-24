package commandcenter;

import Utilities.Topics;
import Utilities.Message;
import Utilities.Protocol;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Polls both the operator API and the upstream bus's GET queues on one worker. */
public final class SoftwareBusManager implements Manager {
    private static final int[] STATUS_TOPICS = {
        Topics.FIRE, Topics.ELEVATOR_COMMAND, Topics.CABIN_STATUS, Topics.DOOR_STATUS
    };
    private static final long STALE_AFTER = TimeUnit.SECONDS.toNanos(5);
    // Rows follow STATUS_TOPICS. Fire uses column 0; elevators use columns 1..4.
    private final long[][] lastStatus = new long[STATUS_TOPICS.length][CommandCenterApi.ELEVATORS + 1];
    private final boolean[][] fresh = new boolean[STATUS_TOPICS.length][CommandCenterApi.ELEVATORS + 1];
    private final String host;
    private final int port;
    private final Deque<Message> outgoing = new ArrayDeque<>();
    private volatile Socket socket;
    private volatile boolean stopped;
    private CommandCenterApi api;
    private ScheduledExecutorService worker;

    /** Message layouts follow the Team 3 Software Bus Message Catalog. */
    public SoftwareBusManager(String host, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid bus port");
        this.host = host;
        this.port = port;
    }

    @Override public synchronized void start(CommandCenterApi api) {
        if (worker != null) throw new IllegalStateException("Manager already started");
        this.api = api;
        clearFreshness(true);
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "command-center-bus-poller");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::poll, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void connect() throws IOException {
        Socket next = new Socket();
        socket = next; // stop() can interrupt a connection attempt or a blocked GET
        if (stopped) { disconnect(); return; }
        next.connect(new InetSocketAddress(host, port), 2000);
        next.setSoTimeout(2000);
        send(Message.of(Protocol.REGISTER, 0, 0));
        // REGISTER has its own reply. Read it before sending any GET requests.
        Message registration = Protocol.readMessage(next.getInputStream());
        if (registration == null || registration.topic() != Protocol.REGISTER || registration.subtopic() == 0) {
            throw new IOException("Bus registration failed");
        }
        for (int topic : STATUS_TOPICS) {
            // One wildcard queue receives all elevators without duplicate updates.
            send(Message.of(Protocol.SUBSCRIBE, topic, Topics.ALL));
        }
        System.out.println("Command Center connected to software bus at " + host + ":" + port);
    }

    private void poll() {
        if (stopped) return;
        try {
            if (socket == null) connect();
            if (stopped) return;
            if (api.fireTest()) outgoing.add(Message.fire(true));
            if (api.fireReset()) outgoing.add(Message.fire(false));
            for (int id = 1; id <= CommandCenterApi.ELEVATORS; id++) {
                if (api.takeOutOfService(id)) outgoing.add(Message.elevatorCommand(id, false));
                if (api.returnToService(id)) outgoing.add(Message.elevatorCommand(id, true));
            }
            while (!outgoing.isEmpty()) {
                send(outgoing.peek());
                outgoing.remove();
            }
            for (int topic : STATUS_TOPICS) {
                // Limit work per queue so status traffic cannot starve operator actions.
                for (int n = 0; n < 32; n++) {
                    send(Message.of(Protocol.GET, topic, Topics.ALL));
                    Message m = Protocol.readMessage(socket.getInputStream());
                    if (m == null) throw new EOFException("Bus disconnected");
                    if (m.topic() == Protocol.NO_MESSAGE) break;
                    if (m.topic() != topic) throw new IOException("Unexpected reply to GET " + topic);
                    apply(m);
                }
            }
            expireStatus();
        } catch (IOException | RuntimeException e) {
            disconnect();
            clearFreshness(false);
            if (!stopped) System.err.println("Software bus poll failed; retrying: " + e.getMessage());
        }
    }

    private void apply(Message m) {
        if (m.topic() == Topics.FIRE) {
            if (m.subtopic() != Topics.NONE || m.payload(1) > 1) return;
            // The catalog has one fire value: 1 = fire alert / test fire, 0 = reset.
            api.setFireStatus(m.flag(1) ? FireStatus.ALARM : FireStatus.NORMAL);
            received(0, 0);
            return;
        }
        int id = m.subtopic();
        if (id < 1 || id > CommandCenterApi.ELEVATORS) return;
        switch (m.topic()) {
            case Topics.CABIN_STATUS -> {
                int floor = m.payload(2), dir = m.payload(3);
                if (m.payload(1) > 1 || floor < Message.MIN_FLOOR
                        || floor > CommandCenterApi.FLOORS || dir > Message.DIR_DOWN) return;
                api.setCurrentFloor(id, floor);
                api.setDirection(id, switch (dir) {
                    case Message.DIR_UP -> Direction.UP;
                    case Message.DIR_DOWN -> Direction.DOWN;
                    default -> Direction.NONE;
                });
                received(2, id);
            }
            case Topics.DOOR_STATUS -> {
                for (int n = 1; n <= 4; n++) if (m.payload(n) > 1) return;
                if (m.flag(1) && m.flag(2)) {
                    System.err.println("Ignoring contradictory door status for elevator " + id);
                    return;
                }
                api.setDoorStatus(id, m.flag(1) ? DoorStatus.OPEN
                        : m.flag(2) ? DoorStatus.CLOSED : DoorStatus.TRANSITION);
                api.setObstruction(id, m.flag(3));
                api.setOverload(id, m.flag(4));
                received(3, id);
            }
            case Topics.ELEVATOR_COMMAND -> {
                if (m.payload(1) > 1) return;
                // Our demo controller reuses this topic for service confirmation.
                api.setServiceStatus(id, m.flag(1) ? ServiceStatus.IN_SERVICE : ServiceStatus.OUT_OF_SERVICE);
                received(1, id);
            }
            default -> { }
        }
    }

    private void received(int topicIndex, int id) {
        lastStatus[topicIndex][id] = System.nanoTime();
        if (!fresh[topicIndex][id]) setFresh(topicIndex, id, true);
    }

    private void expireStatus() {
        long now = System.nanoTime();
        for (int topic = 0; topic < STATUS_TOPICS.length; topic++) {
            int first = topic == 0 ? 0 : 1;
            int last = topic == 0 ? 0 : CommandCenterApi.ELEVATORS;
            for (int id = first; id <= last; id++) {
                if (fresh[topic][id] && now - lastStatus[topic][id] >= STALE_AFTER) setFresh(topic, id, false);
            }
        }
    }

    private void setFresh(int topicIndex, int id, boolean value) {
        fresh[topicIndex][id] = value;
        switch (STATUS_TOPICS[topicIndex]) {
            case Topics.FIRE -> api.setFireStatusFresh(value);
            case Topics.ELEVATOR_COMMAND -> api.setServiceStatusFresh(id, value);
            case Topics.CABIN_STATUS -> api.setCabinStatusFresh(id, value);
            case Topics.DOOR_STATUS -> api.setDoorStatusFresh(id, value);
            default -> { }
        }
    }

    private void clearFreshness(boolean resetAll) {
        for (int topic = 0; topic < STATUS_TOPICS.length; topic++) {
            int first = topic == 0 ? 0 : 1;
            int last = topic == 0 ? 0 : CommandCenterApi.ELEVATORS;
            for (int id = first; id <= last; id++) {
                lastStatus[topic][id] = 0;
                if (resetAll || fresh[topic][id]) setFresh(topic, id, false);
            }
        }
    }

    private void send(Message m) throws IOException {
        Protocol.writeMessage(socket.getOutputStream(), m);
    }

    private void disconnect() {
        Socket old = socket;
        socket = null;
        if (old != null) try { old.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void stop() {
        stopped = true;
        disconnect();
        if (worker != null) worker.shutdownNow();
    }
}
