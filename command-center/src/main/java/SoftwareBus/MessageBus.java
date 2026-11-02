package SoftwareBus;

import Utilities.Message;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MessageBus {
    private final Set<SoftwareBusClient> clients = ConcurrentHashMap.newKeySet();
    private final Set<RouterLink> links = ConcurrentHashMap.newKeySet();

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

    public void broadcastFromClient(Message message, SoftwareBusClient sender) {
        int topic = message.topic();
        int subtopic = message.subtopic();
        long value = message.value();

        System.out.printf("Broadcast from client: Topic %d, Subtopic %d, Value %d%n", topic, subtopic, value);

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
        int topic = message.topic();
        int subtopic = message.subtopic();
        long value = message.value();

        System.out.printf("Broadcast from router: Topic %d, Subtopic %d, Value %d%n", topic, subtopic, value);

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
