package Utilities;

import java.util.Arrays;

/**
 * One Software Bus message: 8 bytes
 *
 * <pre>
 *  wire index:   0        1         2      3      4      5      6      7
 *             +-------+----------+------+------+------+------+------+------+
 *             | TOPIC | SUBTOPIC |  P1  |  P2  |  P3  |  P4  |  P5  |  P6  |
 *             +-------+----------+------+------+------+------+------+------+
 *                                 \________________ body (6 bytes) _______/
 * </pre>
 *
 * The message catalog numbers payload bytes from 1, so "payload byte n" lives at
 * wire index n + 1 (body index n - 1). Use payload(int) with the catalog's
 * numbering, and you never have to do that conversion yourself.
 *
 * Topic and subtopic are unsigned (0-255). For per-elevator topics the subtopic is
 * the elevator number (1-4); building-wide topics (FIRE, UP_CALL, DOWN_CALL,
 * CLEAR_REQUEST) use subtopic 0. On a subscription, subtopic 0 means "any elevator".
 *
 * Build catalog messages with the named factories ({@link #cabinStatus},
 * {@link #upCall}, ...): they put each field in the right payload byte and reject
 * out-of-range values. Do NOT use {@link #of(int, int, long)} for catalog messages:
 * it stores a big-endian number, so a small value lands in payload byte 6, not 1.
 * {@code of} is kept for bus commands (SUBSCRIBE etc.), which carry their target
 * subtopic in the last body byte.
 *
 * Messages are immutable (safe to share between threads).
 */

public final class Message {

    public static final int SIZE = 8;
    public static final int BODY_SIZE = 6;
    //Payload bytes are numbered 1..PAYLOAD_BYTES, as in the catalog
    public static final int PAYLOAD_BYTES = BODY_SIZE;
    // Largest number that fits in the 6-byte body
    public static final long MAX_VALUE = (1L << 48) - 1;

    //Catalog value ranges
    public static final int MIN_FLOOR = 1;
    public static final int MAX_FLOOR = 10;
    public static final int MIN_ELEVATOR = 1;
    public static final int MAX_ELEVATOR = 4;

    // Direction codes (CABIN_STATUS, MOTOR_COMMAND, ARRIVAL_CHIME, CLEAR_REQUEST)
    public static final int DIR_IDLE = 0;   // "idle" in CABIN_STATUS, "none" in ARRIVAL_CHIME
    public static final int DIR_UP = 1;
    public static final int DIR_DOWN = 2;
    public static final int DIR_BOTH = 3;   // CLEAR_REQUEST only

    // Alignment codes (ALIGN)
    public static final int ALIGN_NONE = 0;
    public static final int ALIGN_BOTTOM = 1;
    public static final int ALIGN_TOP = 2;
    public static final int ALIGN_BOTH = 3;

    private final int topic;
    private final int subtopic;
    private final byte[] body;

    private Message(int topic, int subtopic, byte[] body) {
        this.topic = checkByte("topic", topic);
        this.subtopic = checkByte("subtopic", subtopic);
        this.body = body;
    }

    //Generic builders for different message types
    // Message whose body is a number (0 .. 2^48-1). Used for bus commands.
    public static Message of(int topic, int subtopic, long value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException("value out of range for 6-byte body: " + value);
        }
        byte[] body = new byte[BODY_SIZE];
        for (int i = BODY_SIZE - 1; i >= 0; i--) {
            body[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return new Message(topic, subtopic, body);
    }

    // Message with a raw body of up to 6 bytes (shorter bodies are zero-padded)
    public static Message ofBytes(int topic, int subtopic, byte[] rawBody) {
        if (rawBody.length > BODY_SIZE) {
            throw new IllegalArgumentException("body longer than " + BODY_SIZE + " bytes");
        }
        return new Message(topic, subtopic, Arrays.copyOf(rawBody, BODY_SIZE));
    }

    /**
     * Message built from payload bytes in catalog order: the first argument is
     * payload byte 1 (wire index 2), the second is payload byte 2, and so on.
     * Unused payload bytes are 0. Each value must be 0-255.
     */
    public static Message ofPayload(int topic, int subtopic, int... payload) {
        if (payload.length > PAYLOAD_BYTES) {
            throw new IllegalArgumentException("at most " + PAYLOAD_BYTES + " payload bytes, got " + payload.length);
        }
        byte[] body = new byte[BODY_SIZE];
        for (int i = 0; i < payload.length; i++) {
            body[i] = (byte) checkByte("payload byte " + (i + 1), payload[i]);
        }
        return new Message(topic, subtopic, body);
    }

    // Decodes the 8-byte wire form
    public static Message fromBytes(byte[] wire) {
        if (wire.length != SIZE) {
            throw new IllegalArgumentException("message must be " + SIZE + " bytes, got " + wire.length);
        }
        return new Message(wire[0] & 0xFF, wire[1] & 0xFF, Arrays.copyOfRange(wire, 2, SIZE));
    }

    // Encodes to the 8-byte wire form
    public byte[] toBytes() {
        byte[] wire = new byte[SIZE];
        wire[0] = (byte) topic;
        wire[1] = (byte) subtopic;
        System.arraycopy(body, 0, wire, 2, BODY_SIZE);
        return wire;
    }

    // Catalog message builders
    // One factory per catalog topic. Parameters are in payload-byte order.

    // ---- Publisher: Door

    /** DOOR_STATUS: P1 fully open, P2 fully closed, P3 obstructed, P4 overweight (1 = yes). */
    public static Message doorStatus(int elevator, boolean fullyOpen, boolean fullyClosed,
                                     boolean obstructed, boolean overweight) {
        return ofPayload(Topics.DOOR_STATUS, checkElevator(elevator),
                bit(fullyOpen), bit(fullyClosed), bit(obstructed), bit(overweight));
    }

    // ---- Publisher: Cabin

    /** ALIGN: P1 alignment (ALIGN_NONE, ALIGN_BOTTOM, ALIGN_TOP, ALIGN_BOTH). */
    public static Message align(int elevator, int alignment) {
        return ofPayload(Topics.ALIGN, checkElevator(elevator),
                checkRange("alignment", alignment, ALIGN_NONE, ALIGN_BOTH));
    }

    /** CABIN_STATUS: P1 stopped (1 = stopped), P2 floor 1-10, P3 direction (DIR_IDLE, DIR_UP, DIR_DOWN). */
    public static Message cabinStatus(int elevator, boolean stopped, int floor, int direction) {
        return ofPayload(Topics.CABIN_STATUS, checkElevator(elevator),
                bit(stopped), checkFloor(floor), checkRange("direction", direction, DIR_IDLE, DIR_DOWN));
    }

    /**
     * MOTOR_COMMAND: P1 motor (0 = stop, 1 = start), P2 direction (DIR_UP, DIR_DOWN).
     * Starting requires DIR_UP or DIR_DOWN. When stopping, direction may also be DIR_IDLE (0).
     */
    public static Message motorCommand(int elevator, boolean start, int direction) {
        int min = start ? DIR_UP : DIR_IDLE;
        return ofPayload(Topics.MOTOR_COMMAND, checkElevator(elevator),
                bit(start), checkRange("direction", direction, min, DIR_DOWN));
    }

    /** DOOR_COMMAND: P1 doors (0 = close, 1 = open). */
    public static Message doorCommand(int elevator, boolean open) {
        return ofPayload(Topics.DOOR_COMMAND, checkElevator(elevator), bit(open));
    }

    /** BUZZER: P1 buzzer (0 = off, 1 = on). */
    public static Message buzzer(int elevator, boolean on) {
        return ofPayload(Topics.BUZZER, checkElevator(elevator), bit(on));
    }

    /** ARRIVAL_CHIME: P1 floor 1-10, P2 direction the car will leave in (DIR_IDLE = none, DIR_UP, DIR_DOWN). */
    public static Message arrivalChime(int elevator, int floor, int leavingDirection) {
        return ofPayload(Topics.ARRIVAL_CHIME, checkElevator(elevator),
                checkFloor(floor), checkRange("direction", leavingDirection, DIR_IDLE, DIR_DOWN));
    }

    // ---- Publisher: Hallway Floor Panel

    /** UP_CALL (subtopic 0): P1 floor 1-10. */
    public static Message upCall(int floor) {
        return ofPayload(Topics.UP_CALL, Topics.NONE, checkFloor(floor));
    }

    /** DOWN_CALL (subtopic 0): P1 floor 1-10. */
    public static Message downCall(int floor) {
        return ofPayload(Topics.DOWN_CALL, Topics.NONE, checkFloor(floor));
    }

    /**
     * FIRE (subtopic 0): P1 = 1 for a fire alert (Hallway Floor Panel) or test fire
     * (Command Center), 0 = reset (Command Center).
     */
    public static Message fire(boolean on) {
        return ofPayload(Topics.FIRE, Topics.NONE, bit(on));
    }

    // ---- Publisher: Elevator Panel

    /** CABIN_CALL: P1 floor 1-10. */
    public static Message cabinCall(int elevator, int floor) {
        return ofPayload(Topics.CABIN_CALL, checkElevator(elevator), checkFloor(floor));
    }

    /** KEY: P1 key (0 = off, 1 = on). */
    public static Message key(int elevator, boolean on) {
        return ofPayload(Topics.KEY, checkElevator(elevator), bit(on));
    }

    // ---- Publisher: Control Center

    /** GOTO: P1 floor 1-10, keep doors closed on arrival. ("goto" is a reserved word in Java.) */
    public static Message gotoFloor(int elevator, int floor) {
        return ofPayload(Topics.GOTO, checkElevator(elevator), checkFloor(floor));
    }

    /** SERVICE: P1 floor 1-10, open doors on arrival. */
    public static Message service(int elevator, int floor) {
        return ofPayload(Topics.SERVICE, checkElevator(elevator), checkFloor(floor));
    }

    /** CLEAR_REQUEST (subtopic 0): P1 hallway floor 1-10, P2 button (DIR_UP, DIR_DOWN, DIR_BOTH). */
    public static Message clearRequest(int floor, int buttonDirection) {
        return ofPayload(Topics.CLEAR_REQUEST, Topics.NONE,
                checkFloor(floor), checkRange("button direction", buttonDirection, DIR_UP, DIR_BOTH));
    }

    /** CLEAR_BUTTONS: P1 elevator panel button 1-10. */
    public static Message clearButtons(int elevator, int floorButton) {
        return ofPayload(Topics.CLEAR_BUTTONS, checkElevator(elevator), checkFloor(floorButton));
    }

    // ---- Publisher: Command Center
    // (FIRE reset / test fire uses fire(...) above.)

    /** ELEVATOR_COMMAND: P1 service state (0 = out of service, 1 = in service). */
    public static Message elevatorCommand(int elevator, boolean inService) {
        return ofPayload(Topics.ELEVATOR_COMMAND, checkElevator(elevator), bit(inService));
    }

    public int topic() { return topic; }

    public int subtopic() { return subtopic; }

    /**
     * Payload byte n as an unsigned value 0-255, using the catalog's numbering:
     * payload(1) is the first byte after the subtopic (wire index 2).
     * e.g. for CABIN_STATUS: payload(1) = stopped, payload(2) = floor, payload(3) = direction.
     */
    public int payload(int n) {
        if (n < 1 || n > PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload byte must be 1-" + PAYLOAD_BYTES + ", got " + n);
        }
        return body[n - 1] & 0xFF;
    }

    /** Payload byte n read as a yes/no flag (non-zero = true). */
    public boolean flag(int n) {
        return payload(n) != 0;
    }

    // Copy of the 6 body bytes
    public byte[] body() { return body.clone(); }

    // Body read as a big-endian unsigned 48-bit number
    public long value() {
        long v = 0;
        for (byte b : body) {
            v = (v << 8) | (b & 0xFF);
        }
        return v;
    }

    /**
     * True if this message should be delivered to a subscription on
     * (topic, subtopic). Topics must match exactly; subtopic 0 on either side
     * is a wildcard and will deliver messages from all matching subtopics
     * (subscribing with 0 receives every subtopic, and a
     * message published with 0 reaches subscribers of any subtopic).
     */
    public boolean matches(int topic, int subtopic) {
        return this.topic == topic
                && (subtopic == Topics.ALL || this.subtopic == Topics.ALL || this.subtopic == subtopic);
    }

    // Remainder of the file are helper functions
    private static int checkByte(String name, int v) {
        if (v < 0 || v > 255) {
            throw new IllegalArgumentException(name + " must be 0-255, got " + v);
        }
        return v;
    }

    private static int bit(boolean b) {
        return b ? 1 : 0;
    }

    private static int checkElevator(int elevator) {
        return checkRange("elevator", elevator, MIN_ELEVATOR, MAX_ELEVATOR);
    }

    private static int checkFloor(int floor) {
        return checkRange("floor", floor, MIN_FLOOR, MAX_FLOOR);
    }

    private static int checkRange(String name, int v, int min, int max) {
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + " must be " + min + "-" + max + ", got " + v);
        }
        return v;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Message)) return false;
        Message m = (Message) o;
        return topic == m.topic && subtopic == m.subtopic && Arrays.equals(body, m.body);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * topic + subtopic) + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "[" + Topics.topicName(topic) + " | " + Topics.subtopicName(subtopic) + " | " + describePayload() + "]";
    }

    private String describePayload() {
        switch (topic) {
            case Topics.DOOR_STATUS:
                return "open=" + payload(1) + " closed=" + payload(2)
                        + " obstructed=" + payload(3) + " overweight=" + payload(4);
            case Topics.ALIGN:            return "alignment=" + payload(1);
            case Topics.CABIN_STATUS:
                return "stopped=" + payload(1) + " floor=" + payload(2) + " dir=" + payload(3);
            case Topics.MOTOR_COMMAND:    return "start=" + payload(1) + " dir=" + payload(2);
            case Topics.DOOR_COMMAND:     return "open=" + payload(1);
            case Topics.BUZZER:           return "on=" + payload(1);
            case Topics.ARRIVAL_CHIME:    return "floor=" + payload(1) + " leavingDir=" + payload(2);
            case Topics.UP_CALL:
            case Topics.DOWN_CALL:
            case Topics.CABIN_CALL:
            case Topics.GOTO:
            case Topics.SERVICE:          return "floor=" + payload(1);
            case Topics.FIRE:             return "fire=" + payload(1);
            case Topics.KEY:              return "key=" + payload(1);
            case Topics.CLEAR_REQUEST:    return "floor=" + payload(1) + " button=" + payload(2);
            case Topics.CLEAR_BUTTONS:    return "button=" + payload(1);
            case Topics.ELEVATOR_COMMAND: return "inService=" + payload(1);
            default:                      return "value=" + value(); // bus commands / unknown topics
        }
    }
}