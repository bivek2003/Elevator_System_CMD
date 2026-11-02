package Utilities;

import java.io.*;

/**
 * The wire protocol, the shared contract between the SoftwareBusClient
 * and the SoftwareBusServer.
 *
 * <h2>Client &lt;-&gt; Bus</h2>
 * Every frame is a fixed 8-byte Message. Byte 0 tells the bus what the frame is:
 * a value of 0xF0 or higher is a command, anything lower is a topic, and the
 * frame is published to every other connection. Commands carry the target topic
 * in byte 1 and the target subtopic in the last byte of the body. Only GET gets
 * a response; TCP already guarantees delivery, so there are no ACKs.
 * <pre>
 *  REQUEST                         RESPONSE
 *  (publish)   8-byte message      none
 *  REGISTER    (no payload)        none
 *  SUBSCRIBE   topic, subtopic     none
 *  UNSUBSCRIBE topic, subtopic     none
 *  GET         topic, subtopic     next queued message   or   NO_MESSAGE
 * </pre>
 *
 * <h2>Bus &lt;-&gt; Router</h2>
 * Raw 8-byte messages only, no commands. The router forwards each message
 * to every other bus. The bus doesn't send a message it received from the router
 * back to the router, which prevents loops.
 */

public class Protocol {

    private Protocol() {}

    public static final int FIRST_COMMAND = 0xF0;

    // Requests (client -> bus)
    //Note PUBLISH is not needed because anything that is not a command is treated as PUBLISH
    public static final int REGISTER    = 0xF0;
    public static final int SUBSCRIBE   = 0xF1;
    public static final int UNSUBSCRIBE = 0xF2;
    public static final int GET         = 0xF3;
    public static final int NO_MESSAGE  = 0xF4;

    public static boolean isCommand(int topic) {
        return topic >= FIRST_COMMAND;
    }

    // Reads exactly one 8-byte message from a socket stream (blocks until it arrives)
    public static Message readMessage(InputStream in) throws IOException {
        byte[] wire = in.readNBytes(Message.SIZE);

        // Format checks:
        if (wire.length == 0) {
            return null;
        }

        if (wire.length != Message.SIZE) {
            throw new EOFException("Incomplete message received");
        }

        return Message.fromBytes(wire);
    }

    // Writes a message's 8 bytes to a socket stream (caller flushes).
    public static void writeMessage(OutputStream out, Message m) throws IOException {
        out.write(m.toBytes());
        out.flush();
    }
}
