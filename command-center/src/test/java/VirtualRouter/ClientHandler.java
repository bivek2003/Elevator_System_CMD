package VirtualRouter;

import Utilities.Message;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable{

    private final Socket socket;
    private final RoutingHandler router;

    private final InputStream input;
    private final OutputStream output;

    public ClientHandler(Socket socket, RoutingHandler router) throws IOException {
        this.socket = socket;
        this.router = router;

        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
    }

    @Override
    public void run() {
        router.addClient(this);

        try (socket) {

            while (true) {
                byte[] data = input.readNBytes(Message.SIZE);

                if (data.length == 0) {
                    break;
                }

                if (data.length != Message.SIZE) {
                    throw new EOFException("Incomplete message received");
                }

                Message message = Message.fromBytes(data);
                router.broadcast(message, this);
            }
        } catch (IOException e) {
            System.out.println("Client connection error: " + e.getMessage());
        } finally {
            // Remove client upon disconnect
            router.removeClient(this);
        }
    }

    public synchronized void send(byte[] data) throws IOException {
        output.write(data);
        output.flush();
    }

    public void disconnect() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
