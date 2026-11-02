package SoftwareBus;

import Utilities.Message;
import Utilities.Protocol;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The bus's TCP connection to the Virtual Router, on its own thread.
 *
 * Connects (and reconnects every few seconds if the router is down or drops),
 * so buses and the router can be started in any order. Incoming messages are
 * delivered to local subscribers; The link forwards local publications.
 * If the router is unreachable, local delivery keeps working and outgoing
 * messages are dropped with a terminal printout.
 */

public class RouterLink implements Runnable {

    private static final int RETRY_MS = 2000;
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final MessageBus bus;
    private final String host;
    private final int port;
    private final BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>();
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean running = true;

    RouterLink(MessageBus bus, String host, int port) {
        this.bus = bus;
        this.host = host;
        this.port = port;
    }

    public void send(Message m) {
        int topic = m.topic();
        int subtopic = m.subtopic();
        long value = m.value();

        if (m != null) {
            System.out.printf("Pushing Message: Topic %d, Subtopic %d, Value %d%n", topic, subtopic, value);
            sendQueue.offer(m);
        }
    }

    @Override
    public void run() {
        bus.addLink(this);

        Thread writerThread = null;

        try {
            socket = new Socket(host, port);

            out = socket.getOutputStream();
            in = socket.getInputStream();

            writerThread = new Thread(() -> {
                try {
                    while (running && !socket.isClosed()) {
                        // wait for a message
                        Message m = sendQueue.take();

                        System.out.printf("Sending to router: Topic %d, Subtopic %d, Value %d%n", m.topic(), m.subtopic(), m.value());
                        Protocol.writeMessage(out, m);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    System.out.println("Router write error: " + e.getMessage());

                    // Unblock the reader thread too
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                }
            });

            writerThread.start();

            while (running && !socket.isClosed()) {
                // Read and broadcast incoming messages
                Message m = Protocol.readMessage(in);

                if (m == null) {
                    break;
                }

                bus.broadcastFromRouter(m, this);
            }
        } catch (IOException e) {
            System.out.println("Router connection error: " + e.getMessage());
        } finally {
            running = false;

            // Interrupt the writer if blocked on take()
            if (writerThread != null) {
                writerThread.interrupt();
            }

            // Remove client upon disconnect
            bus.removeLink(this);
        }

    }

    void close() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
