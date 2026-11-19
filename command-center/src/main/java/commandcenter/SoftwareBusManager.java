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
        for (int topic : STATUS_TOPICS) {
            // Upstream ALL subscriptions do not receive individual elevator subtopics.
            for (int id = 0; id <= CommandCenterApi.ELEVATORS; id++) {
                send(Message.of(Protocol.SUBSCRIBE, topic, id));
            }
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
                for (int id = 0; id <= CommandCenterApi.ELEVATORS; id++) {
                    // Bound work per queue so heavy status traffic cannot starve operator polls.
                    for (int n = 0; n < 32; n++) {
                        send(Message.of(Protocol.GET, topic, id));
                        Message m = Protocol.readMessage(socket.getInputStream());
                        if (m == null) throw new EOFException("Bus disconnected");
                        if (m.topic() == Protocol.NO_MESSAGE) break;
                        // Broadcasts also appear in every exact elevator queue; apply only once.
                        if (m.subtopic() != Topics.ALL || id == Topics.ALL) apply(m);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            disconnect();
            if (!stopped) System.err.println("Software bus poll failed; retrying: " + e.getMessage());
        }
    }

    private void apply(Message m) {
        if (m.topic() == Topics.FIRE) {
            // The catalog has one fire value: 1 = fire alert / test fire, 0 = reset.
            api.setFireStatus(m.flag(1) ? FireStatus.ALARM : FireStatus.NORMAL);
            return;
        }
        int first = m.subtopic() == Topics.ALL ? 1 : m.subtopic();
        int last = m.subtopic() == Topics.ALL ? CommandCenterApi.ELEVATORS : first;
        if (first < 1 || last > CommandCenterApi.ELEVATORS) return;
        for (int id = first; id <= last; id++) {
            switch (m.topic()) {
                case Topics.CABIN_STATUS -> {
                    int floor = m.payload(2), dir = m.payload(3);
                    if (floor >= Message.MIN_FLOOR && floor <= CommandCenterApi.FLOORS) api.setCurrentFloor(id, floor);
                    if (dir <= Message.DIR_DOWN) api.setDirection(id, switch (dir) {
                        case Message.DIR_UP -> Direction.UP;
                        case Message.DIR_DOWN -> Direction.DOWN;
                        default -> Direction.NONE;
                    });
                }
                case Topics.DOOR_STATUS -> {
                    if (m.flag(1)) api.setDoorStatus(id, DoorStatus.OPEN);
                    else if (m.flag(2)) api.setDoorStatus(id, DoorStatus.CLOSED);
                    api.setObstruction(id, m.flag(3));
                    api.setOverload(id, m.flag(4));
                }
                case Topics.ELEVATOR_COMMAND ->
                    api.setServiceStatus(id, m.flag(1) ? ServiceStatus.IN_SERVICE : ServiceStatus.OUT_OF_SERVICE);
                default -> { }
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
