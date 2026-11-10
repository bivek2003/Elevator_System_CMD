package SoftwareBus;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Runs a Software Bus as a stand-alone
 *
 * Usage:
 *   java SoftwareBus.SBMain &lt;listenPort&gt; [routerHost routerPort]
 *
 * Example:
 *   java SoftwareBus.SBMain 6000                     stand-alone bus, no router
 *   java SoftwareBus.SBMain 6000 localhost 5000      bus + router on this machine
 *   java SoftwareBus.SBMain 6000 192.168.8.10 5000   router on another machine
 */

public class SBMain {

    public static void main(String[] args) throws Exception {
        MessageBus bus = new MessageBus();
        SoftwareBusServer server;
        RouterLink link;

        try {
            InetAddress localHost = InetAddress.getLocalHost();
            System.out.println("Local IP Address: " + localHost.getHostAddress());
        } catch (UnknownHostException e) {
            System.err.println("Could not resolve local host address: " + e.getMessage());
        }

        if (args.length != 1 && args.length != 3) {
            System.err.println("Usage: java SoftwareBus.SBMain <listenPort> [routerHost routerPort]");
            System.exit(1);
        }
        int listenPort = Integer.parseInt(args[0]);
        if (args.length == 3) {
            server = new SoftwareBusServer(listenPort, bus);
            link = new RouterLink(bus, args[1], Integer.parseInt(args[2]));

            Thread thread = new Thread(link);
            thread.start();

            server.start();
        } else {
            server = new SoftwareBusServer(listenPort, bus);
            server.start();
        }
    }
}
