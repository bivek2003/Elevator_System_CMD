package SoftwareBus;

import Utilities.Message;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class SoftwareBusServer {

    private final int listenPort;
    private final MessageBus bus;

    public SoftwareBusServer(int listenPort, MessageBus bus) {
        this.listenPort = listenPort;
        this.bus = bus;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
            System.out.println("Server listening on port " + listenPort);

            int id = 0;

            // Listen loop
            while (true) {
                Socket socket = serverSocket.accept();

                System.out.println("Client connected: " + socket.getRemoteSocketAddress());

                // Create a new client and attach them to the internal bus
                SoftwareBusClient client = new SoftwareBusClient(socket, bus, id);

                // Launch a new thread for the client
                Thread thread = new Thread(client);
                thread.start();

                id++;
            }
        }
    }
}
