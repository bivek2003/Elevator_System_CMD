package VirtualRouter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {

    private final int port;
    private final RoutingHandler router;

    public Server(int port) {
        this.port = port;
        this.router = new RoutingHandler();
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);

            while (true) {
                // Waits on a new connection
                Socket socket = serverSocket.accept();

                System.out.println("Client connected: " + socket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(socket, router);

                Thread thread = new Thread(handler);
                thread.start();
            }
        }
    }
}
