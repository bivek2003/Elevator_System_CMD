package Utilities;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public class DeviceRegister {
    private final Set<Integer> activeIds = new HashSet<>(); // Currently assigned IDs
    private final TreeSet<Integer> freeIds = new TreeSet<>(); // Inactive IDs sorted least to greatest

    private final int MAX_ID = 255;
    private int nextId = 1;

    public synchronized int register() {
        int id;

        if (!freeIds.isEmpty()) {
            id = freeIds.pollFirst();
        } else {
            if (nextId > MAX_ID) {
                throw new IllegalStateException("No available device IDs");
            }

            id = nextId++;
        }

        activeIds.add(id);
        return id;
    }

    public synchronized void deregister(int id) {
        if (activeIds.remove(id)) {
            freeIds.add(id);
        }
    }

}
