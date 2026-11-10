package VirtualRouter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class VRMain {

    public static void main(String[] args) throws IOException {
        int port = 5555; // Default port

        try {
            InetAddress localHost = InetAddress.getLocalHost();
            System.out.println("Local IP Address: " + localHost.getHostAddress());
        } catch (UnknownHostException e) {
            System.err.println("Could not resolve local host address: " + e.getMessage());
        }

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Error: '" + args[0] + "' is not a valid port number.");
            }
        }

        Server server = new Server(port);
        server.start();
    }
}
