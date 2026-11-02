package Utilities;

import SoftwareBus.Topics;

import java.util.Arrays;

/**
 * One Software Bus message: 8 bytes
 *
 * <pre>
 *   byte 0     byte 1       bytes 2..7
 * +--------+-----------+-------------------+
 * | TOPIC  | SUBTOPIC  |   BODY (6 bytes)  |
 * +--------+-----------+-------------------+
 * </pre>
 *
 * Topic and subtopic are unsigned (0-255). Subtopic 0 means "all elevators".
 * The body is 6 bytes; most messages will most likely just carry a
 * number, so helper functions store/read the body as a big-endian
 * unsigned 48-bit integer.
 *
 * Messages are immutable (safe to share between threads).
 */

public final class Message {

    public static final int SIZE = 8;
    public static final int BODY_SIZE = 6;
    // Largest number that fits in the 6-byte body
    public static final long MAX_VALUE = (1L << 48) - 1;

    private final int topic;
    private final int subtopic;
    private final byte[] body;

    private Message(int topic, int subtopic, byte[] body) {
        this.topic = checkByte("topic", topic);
        this.subtopic = checkByte("subtopic", subtopic);
        this.body = body;
    }

    // Message whose body is a number (0 .. 2^48-1)
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

    public int topic() { return topic; }

    public int subtopic() { return subtopic; }

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

    private static int checkByte(String name, int v) {
        if (v < 0 || v > 255) {
            throw new IllegalArgumentException(name + " must be 0-255, got " + v);
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
        return "[" + Topics.topicName(topic) + " | " + Topics.subtopicName(subtopic) + " | " + value() + "]";
    }
}