package SoftwareBus;

import Utilities.DeviceRegister;
import Utilities.Message;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MessageBus {

    // Per-message logging (publishes, GETs, router traffic). Off by default because
    // polling clients generate thousands of lines. Turn on with MessageBus.DEBUG = true
    // or by starting Java with -Dbus.debug=true
    public static volatile boolean DEBUG = Boolean.getBoolean("bus.debug");

    private final Set<SoftwareBusClient> clients = ConcurrentHashMap.newKeySet();
    private final Set<RouterLink> links = ConcurrentHashMap.newKeySet();
    private final DeviceRegister deviceRegister = new DeviceRegister();

    public void addClient(SoftwareBusClient client) {
        clients.add(client);
    }

    public void addLink(RouterLink link) {
        links.add(link);
    }

    public void removeClient(SoftwareBusClient client) {
        clients.remove(client);
    }

    public void removeLink(RouterLink link) {
        links.remove(link);
    }

    // Unique within this bus. Throws IllegalStateException if all IDs are in use.
    public int registerDevice() {
        return deviceRegister.register();
    }

    public void deregisterDevice(int id) {
        deviceRegister.deregister(id);
    }

    public void broadcastFromClient(Message message, SoftwareBusClient sender) {
        if (DEBUG) {
            System.out.println("Broadcast from client: " + message);
        }

        for (SoftwareBusClient client : clients) {
            if (client == sender) {
                continue;
            }

            client.pushMessage(message);
        }

        for (RouterLink link : links) {
            link.send(message);
        }
    }

    public void broadcastFromRouter(Message message, RouterLink sender) {
        if (DEBUG) {
            System.out.println("Broadcast from router: " + message);
        }
        for (SoftwareBusClient client : clients) {
            client.pushMessage(message);
        }

        for (RouterLink link : links) {
            if (link == sender) {
                continue;
            }

            link.send(message);
        }
    }
}
