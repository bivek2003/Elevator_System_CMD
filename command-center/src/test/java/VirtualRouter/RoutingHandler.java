package VirtualRouter;

import Utilities.Message;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoutingHandler {
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();

    public void addClient(ClientHandler client) {
        clients.add(client);
    }

    public void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    public void broadcast(Message message, ClientHandler sender) {

        byte[] data = message.toBytes();

        System.out.printf("Broadcasting message%n");

        for (ClientHandler client : clients) {
            if (client == sender) {
                continue;
            }

            try {
                client.send(data);
            } catch (IOException e) {
                removeClient(client);
                client.disconnect();
            }
        }
    }
}
