package harness;

import integration.BusConnection;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Shared startup for Test 1 and Test 2. The harness talks only to its bus socket. */
class HarnessMain {
    static void start(String name, int localPort, Set<Integer> elevators,
                      boolean publishesFire, String[] args) throws Exception {
        BusConnection bus = BusConnection.open(localPort, args);
        BusTestController controller = new BusTestController(bus.host, bus.port, elevators, publishesFire);
        Runtime.getRuntime().addShutdownHook(new Thread(controller::close));
        controller.start();
        System.out.println(name + "; bus at " + bus.host + ":" + bus.port + "; elevators " + elevators);
        new CountDownLatch(1).await(); // Keep this process alive until Ctrl+C.
    }
}
