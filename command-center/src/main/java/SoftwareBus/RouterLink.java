package SoftwareBus;

import Utilities.Message;
import Utilities.Protocol;

import java.io.*;
import java.net.InetSocketAddress;
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
    private volatile boolean connected = false;
    private volatile boolean running = true;

    public RouterLink(MessageBus bus, String host, int port) {
        this.bus = bus;
        this.host = host;
        this.port = port;
    }

    // Called by the bus for every local publication
    public void send(Message m) {
        if (m == null) {
            return;
        }
        if (!connected) {
            if (MessageBus.DEBUG) {
                System.out.println("Router not connected, dropping: " + m);
            }
            return;
        }
        sendQueue.offer(m);
    }

    @Override
    public void run() {
        bus.addLink(this);
        try {
            while (running) {
                try {
                    connectAndServe();
                } catch (IOException e) {
                    if (running) {
                        System.out.println("Router link down (" + e.getMessage() + "), retrying in "
                                + RETRY_MS / 1000 + " s");
                    }
                }
                if (!running) {
                    break;
                }
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {

            bus.removeLink(this);
        }

    }

    // One connection: connect, then read until the connection breaks.
    private void connectAndServe() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket = s;
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        sendQueue.clear();
        connected = true;
        System.out.println("Connected to router at " + host + ":" + port);

        Thread writer = new Thread(() -> {
            try {
                while (!s.isClosed()) {
                    Message m = sendQueue.take();   // wait for a message
                    if (MessageBus.DEBUG) {
                        System.out.println("Sending to router: " + m);
                    }
                    Protocol.writeMessage(out, m);
                }
            } catch (InterruptedException e) {
                // reader finished; stop quietly
            } catch (IOException e) {
                System.out.println("Router write error: " + e.getMessage());
                try { s.close(); } catch (IOException ignored) {}   // unblocks the reader
            }
        }, "router-writer");
        writer.setDaemon(true);
        writer.start();

        try {
            while (running) {
                Message m = Protocol.readMessage(in);
                if (m == null) {
                    throw new EOFException("router closed the connection");
                }
                bus.broadcastFromRouter(m, this);
            }
        } finally {
            connected = false;
            writer.interrupt();
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    public void close() {
        running = false;
        connected = false;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
