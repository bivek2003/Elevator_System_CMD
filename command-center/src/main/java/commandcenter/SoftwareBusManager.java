package commandcenter;

import SoftwareBus.Topics;
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
        Topics.FIRE, Topics.MODE, Topics.CAR_POSITION, Topics.CAR_DIRECTION, Topics.DOOR_STATE
    };
    private final String host;
    private final int port;
    private final Long inServiceCode, outOfServiceCode;
    private final long fireTestCode;
    private final Deque<Message> outgoing = new ArrayDeque<>();
    private volatile Socket socket;
    private volatile boolean stopped;
    private CommandCenterApi api;
    private ScheduledExecutorService worker;

    /** Uses the message body codes documented in Software_Bus_Codes.xlsx. */
    public SoftwareBusManager(String host, int port) {
        this(host, port, BusCodes.IN_SERVICE, BusCodes.OUT_OF_SERVICE, BusCodes.FIRE_TEST);
    }

    /** Explicit overrides; null MODE codes disable service commands. */
    public SoftwareBusManager(String host, int port, Long inServiceCode,
                              Long outOfServiceCode, long fireTestCode) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid bus port");
        if ((inServiceCode == null) != (outOfServiceCode == null)) {
            throw new IllegalArgumentException("Configure both service MODE codes");
        }
        if (inServiceCode != null) {
            Message.of(Topics.MODE, Topics.ALL, inServiceCode);
            Message.of(Topics.MODE, Topics.ALL, outOfServiceCode);
            if (inServiceCode.equals(outOfServiceCode)) {
                throw new IllegalArgumentException("Service MODE codes must differ");
            }
        }
        Message.of(Topics.FIRE, Topics.ALL, fireTestCode);
        if (fireTestCode == 0) throw new IllegalArgumentException("Fire test cannot use reset code 0");
        this.host = host;
        this.port = port;
        this.inServiceCode = inServiceCode;
        this.outOfServiceCode = outOfServiceCode;
        this.fireTestCode = fireTestCode;
    }

    @Override public synchronized void start(CommandCenterApi api) {
        if (worker != null) throw new IllegalStateException("Manager already started");
        this.api = api;
        if (inServiceCode == null) {
            System.err.println("Service commands await bus.inServiceCode and bus.outOfServiceCode; "
                    + "service MODE codes are disabled for this connection.");
        }
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
            if (api.fireTest()) outgoing.add(Message.of(Topics.FIRE, Topics.ALL, fireTestCode));
            if (api.fireReset()) outgoing.add(Message.of(Topics.FIRE, Topics.ALL, 0));
            if (inServiceCode != null) {
                for (int id = 1; id <= CommandCenterApi.ELEVATORS; id++) {
                    if (api.takeOutOfService(id)) outgoing.add(Message.of(Topics.MODE, id, outOfServiceCode));
                    if (api.returnToService(id)) outgoing.add(Message.of(Topics.MODE, id, inServiceCode));
                }
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
        long value = m.value();
        if (m.topic() == Topics.FIRE) {
            if (value == 0) api.setFireStatus(FireStatus.NORMAL);
            else if (value == 1) api.setFireStatus(FireStatus.ALARM);
            else if (value == fireTestCode) api.setFireStatus(FireStatus.TEST);
            return;
        }
        int first = m.subtopic() == Topics.ALL ? 1 : m.subtopic();
        int last = m.subtopic() == Topics.ALL ? CommandCenterApi.ELEVATORS : first;
        if (first < 1 || last > CommandCenterApi.ELEVATORS) return;
        for (int id = first; id <= last; id++) {
            switch (m.topic()) {
                case Topics.CAR_POSITION -> {
                    if (value >= 1 && value <= CommandCenterApi.FLOORS) api.setCurrentFloor(id, (int) value);
                }
                case Topics.CAR_DIRECTION -> {
                    if (value <= 2) api.setDirection(id, switch ((int) value) {
                        case 1 -> Direction.UP;
                        case 2 -> Direction.DOWN;
                        default -> Direction.NONE;
                    });
                }
                case Topics.DOOR_STATE -> {
                    if (value == 0) api.setDoorStatus(id, DoorStatus.CLOSED);
                    else if (value == 2) api.setDoorStatus(id, DoorStatus.OPEN);
                }
                case Topics.MODE -> {
                    if (inServiceCode != null && value == inServiceCode) api.setServiceStatus(id, ServiceStatus.IN_SERVICE);
                    else if (outOfServiceCode != null && value == outOfServiceCode) api.setServiceStatus(id, ServiceStatus.OUT_OF_SERVICE);
                }
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
