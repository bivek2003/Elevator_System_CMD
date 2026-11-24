package SoftwareBus;

import Utilities.Message;
import Utilities.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public class SoftwareBusClient implements Runnable{

    private final Socket socket;
    private final MessageBus bus;

    private final InputStream in;
    private final OutputStream out;

    private int id = 0;

    private record TopicKey(int topic, int subtopic) {}

    private static final int MAX_QUEUE_SIZE =1024;

    private final ConcurrentHashMap<TopicKey, BlockingQueue<Message>> topicQueues = new ConcurrentHashMap<>();

    private boolean running = true;

    public SoftwareBusClient(Socket socket, MessageBus bus) throws IOException {
        this.socket = socket;
        this.bus = bus;

        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    // Runs the listening loop for the connected client
    @Override
    public void run() {
        bus.addClient(this);

        try (socket) {
            while (running) {
                try {
                    Message message = Protocol.readMessage(in);
                    // null pointer error checking
                    if (message == null) {
                        throw new NullPointerException("ERROR! message is null! message must never be null!");
                    }
                    // Handle subscription methods here:
                    switch (message.topic()) {
                        case Protocol.REGISTER -> {
                            this.id = register();
                            Protocol.writeMessage(out, Message.of(Protocol.REGISTER, id, 0));
                            if (id == 0) {
                                System.out.println("REGISTER failed: no IDs available");
                            } else {
                                System.out.printf("Registered client with ID %d%n", id);
                            }
                        }
                        case Protocol.SUBSCRIBE -> {
                            // parse subtopic byte as topic and last byte of body as subtopic
                            int topic = message.subtopic();
                            int subtopic = message.body()[Message.BODY_SIZE - 1] & 0xFF;

                            System.out.printf("Received SUBSCRIBE from client %d: Topic %d, Subtopic %d%n", id, topic, subtopic);

                            subscribe(topic, subtopic);
                        }
                        case Protocol.UNSUBSCRIBE -> {
                            // Similar to above but used to remove topic
                            int topic = message.subtopic();
                            int subtopic = message.body()[Message.BODY_SIZE - 1] & 0xFF;

                            System.out.printf("Received UNSUBSCRIBE from client %d: Topic %d, Subtopic %d%n", id, topic, subtopic);

                            unsubscribe(topic, subtopic);
                        }
                        case Protocol.GET -> {
                            // Parse the message and call the getMessage function
                            int topic = message.subtopic();
                            int subtopic = message.body()[Message.BODY_SIZE - 1] & 0xFF;

                            if (MessageBus.DEBUG) {
                                System.out.printf("Received GET from client %d: Topic %d, Subtopic %d%n", id, topic, subtopic);
                            }
                            getMessage(topic, subtopic);
                        }
                        default ->
                            // If not a special message, broadcast to all other connections
                            publish(message);
                    }

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            System.out.println("Client connection error: " + e.getMessage());
        } finally {
            if (id != 0) {
                bus.deregisterDevice(id);
            }
            bus.removeClient(this);
        }
    }

    private void subscribe(int topic, int subtopic) throws IOException {
        TopicKey key = new TopicKey(topic, subtopic);

        topicQueues.computeIfAbsent(
                key,
                k -> new ArrayBlockingQueue<>(MAX_QUEUE_SIZE)
        );
    }

    private void unsubscribe(int topic, int subtopic) {
        TopicKey key = new TopicKey(topic, subtopic);

        topicQueues.remove(key);
    }

    private int register() {
        if (id != 0) {
            return id;              // already registered: keep the same ID
        }
        try {
            return bus.registerDevice();
        } catch (IllegalStateException e) {
            return 0;               // no IDs left; 0 tells the component it failed
        }
    }

    private void publish(Message m) {
        bus.broadcastFromClient(m, this);
    }

    // Puts a message in every subscription queue it matches.
    // Subtopic 0 is a wildcard on either side: a subscription with subtopic 0 gets
    // every elevator, and a message published with subtopic 0 reaches every subscription.
    // If a queue is full, the oldest message is dropped to make room for the new one.
    public void pushMessage(Message m) {
        for (var entry : topicQueues.entrySet()) {
            TopicKey key = entry.getKey();
            if (m.matches(key.topic(), key.subtopic())) {
                BlockingQueue<Message> queue = entry.getValue();
                while (!queue.offer(m)) {
                    Message dropped = queue.poll();   // full: discard the oldest
                    if (MessageBus.DEBUG && dropped != null) {
                        System.out.printf("Queue full for client %d, dropped oldest: %s%n", id, dropped);
                    }
                }
            }
        }
    }

    // Sends a message from the corresponding topic queue to the client.
    private void getMessage(int topic, int subtopic) throws IOException {
        TopicKey key = new TopicKey(topic, subtopic);

        BlockingQueue<Message> queue = topicQueues.get(key);

        Message message = (queue == null) ? null : queue.poll();

        if (message != null) {
            Protocol.writeMessage(out, message);
        } else {
            // Send a response that no message is available
            Protocol.writeMessage(out, Message.of(Protocol.NO_MESSAGE, topic, subtopic));
        }
    }

    // closes i/o and socket
    public void close() throws IOException {
        bus.removeClient(this);
        running = false; // kill while loop
        // kill i/o streams
        out.flush();
        out.close();
        in.close();
        socket.close(); // kill socket
    }
}
