package integration;

import SoftwareBus.SBMain;

/** Our startup adapter. Team 3's SoftwareBus and Utilities sources stay unchanged. */
public class BusConnection {
    public final String host;
    public final int port;

    private BusConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Default: own bus connected to a router. --connect: use an existing bus. */
    public static BusConnection open(int defaultPort, String[] args) {
        if (args.length == 3 && args[0].equals("--connect")) {
            return new BusConnection(args[1], portNumber(args[2]));
        }
        if (args.length != 0 && args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: [routerHost routerPort [localBusPort]] or --connect busHost busPort");
        }
        String routerHost = args.length == 0 ? "localhost" : args[0];
        int routerPort = args.length == 0 ? 5555 : portNumber(args[1]);
        int localPort = args.length == 3 ? portNumber(args[2]) : defaultPort;

        // Use the upstream launcher because it owns the internal RouterLink setup.
        // Its server has no stop method; it ends when this application exits.
        Thread busThread = new Thread(() -> {
            try {
                SBMain.main(new String[] {Integer.toString(localPort), routerHost, Integer.toString(routerPort)});
            } catch (Exception e) {
                System.err.println("Software Bus failed: " + e.getMessage());
                System.exit(1);
            }
        }, "software-bus");
        busThread.setDaemon(true);
        busThread.start();
        return new BusConnection("localhost", localPort);
    }

    private static int portNumber(String text) {
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be in 1..65535");
        return port;
    }
}
