package harness;

import commandcenter.CommandCenterApi;
import commandcenter.Direction;
import commandcenter.DoorStatus;
import commandcenter.FireStatus;
import commandcenter.Manager;
import commandcenter.ServiceStatus;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Simulates this harness's elevators. The API here is the bus adapter, not the panel. */
public class TestManager implements Manager {
    private static final int BOTTOM = 1;
    private static final int DWELL_TICKS = 3;

    private static final class Car {
        final int id;
        int floor, target, dwell, idle = 2;
        Direction dir = Direction.NONE;
        DoorStatus door = DoorStatus.CLOSED;
        boolean obstruction, overload, inService = true;

        Car(int id, int floor) { this.id = id; this.floor = floor; this.target = floor; }
    }

    private final Random rnd = new Random();
    private final boolean simulateFaults;
    private final Car[] cars;
    private CommandCenterApi api;
    private ScheduledExecutorService exec;
    private FireStatus fire = FireStatus.NORMAL;

    public TestManager() { this(true); }

    TestManager(boolean simulateFaults) { this(simulateFaults, Set.of(1, 2, 3, 4)); }

    TestManager(boolean simulateFaults, Set<Integer> elevatorIds) {
        if (elevatorIds.isEmpty()) {
            throw new IllegalArgumentException("Elevator IDs must be a nonempty subset of 1..4");
        }
        for (int id : elevatorIds) {
            if (id < 1 || id > CommandCenterApi.ELEVATORS) {
                throw new IllegalArgumentException("Elevator IDs must be in 1..4");
            }
        }
        this.simulateFaults = simulateFaults;
        this.cars = new Car[elevatorIds.size()];
        int[] startFloors = {3, 1, 5, 2};
        int index = 0;
        for (int id = 1; id <= CommandCenterApi.ELEVATORS; id++) {
            if (elevatorIds.contains(id)) cars[index++] = new Car(id, startFloors[id - 1]);
        }
    }

    @Override
    public synchronized void start(CommandCenterApi api) {
        this.api = api;
        for (Car c : cars) {
            api.setCurrentFloor(c.id, c.floor);
            api.setDirection(c.id, c.dir);
            api.setDoorStatus(c.id, c.door);
            api.setObstruction(c.id, false);
            api.setOverload(c.id, false);
            api.setServiceStatus(c.id, ServiceStatus.IN_SERVICE);
        }
        api.setFireStatus(fire);
        ThreadFactory tf = r -> { Thread t = new Thread(r, "test-manager"); t.setDaemon(true); return t; };
        exec = Executors.newSingleThreadScheduledExecutor(tf);
        exec.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (exec != null) exec.shutdownNow();
    }

    /** Fire test: elevators behave as in fire mode (go to the bottom floor, doors open) until reset. */
    private void onFireTest() {
        fire = FireStatus.TEST;
        api.setFireStatus(fire);
        for (Car c : cars) recall(c);
    }

    private void onFireReset() {
        fire = FireStatus.NORMAL;
        api.setFireStatus(fire);
    }

    /**
     * Taking out of service does not stop the elevator: it travels to the bottom floor and opens
     * its doors, and only then is reported out of service.
     */
    private void onServiceCommand(int id, ServiceStatus target) {
        Car c = null;
        for (Car car : cars) {
            if (car.id == id) c = car;
        }
        if (c == null) throw new IllegalArgumentException("Harness does not own elevator " + id);
        if (target == ServiceStatus.OUT_OF_SERVICE) {
            c.inService = false;
            recall(c);
        } else {
            c.inService = true;
            c.idle = 1;
            api.setServiceStatus(id, ServiceStatus.IN_SERVICE);
        }
    }

    private void recall(Car c) {
        c.target = BOTTOM;
        if (c.floor != BOTTOM && c.door == DoorStatus.OPEN) setDoor(c, DoorStatus.CLOSED);
    }

    private void setDoor(Car c, DoorStatus s) {
        c.door = s;
        api.setDoorStatus(c.id, s);
    }

    private void setDirection(Car c, Direction d) {
        if (c.dir != d) { c.dir = d; api.setDirection(c.id, d); }
    }

    private synchronized void tick() {
        try {
            if (api.fireTest()) onFireTest();
            if (api.fireReset()) onFireReset();
            for (Car c : cars) {
                if (api.takeOutOfService(c.id)) onServiceCommand(c.id, ServiceStatus.OUT_OF_SERVICE);
                if (api.returnToService(c.id)) onServiceCommand(c.id, ServiceStatus.IN_SERVICE);
            }
            for (Car c : cars) step(c);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private boolean held(Car c) { return !c.inService || fire != FireStatus.NORMAL; }

    private void step(Car c) {
        if (simulateFaults) randomFaults(c, !held(c));

        if (c.door == DoorStatus.OPEN) {
            if (c.obstruction || c.overload) return;          // doors stay open while a warning is active
            if (c.dwell > 0) { c.dwell--; return; }
            if (held(c) && c.floor == BOTTOM) {               // parked at the bottom, doors stay open
                if (!c.inService) api.setServiceStatus(c.id, ServiceStatus.OUT_OF_SERVICE);
                return;
            }
            setDoor(c, DoorStatus.CLOSED);
            return;
        }

        if (c.floor == c.target) {
            setDirection(c, Direction.NONE);
            if (held(c)) {
                if (c.floor == BOTTOM) { c.dwell = DWELL_TICKS; setDoor(c, DoorStatus.OPEN); }
                else c.target = BOTTOM;
            } else if (c.idle-- <= 0) {
                c.idle = 1 + rnd.nextInt(3);
                c.target = 1 + rnd.nextInt(CommandCenterApi.FLOORS);
            }
            return;
        }

        setDirection(c, c.target > c.floor ? Direction.UP : Direction.DOWN);
        c.floor += c.dir == Direction.UP ? 1 : -1;
        api.setCurrentFloor(c.id, c.floor);
        if (c.floor == c.target) {
            setDirection(c, Direction.NONE);
            c.dwell = DWELL_TICKS;
            setDoor(c, DoorStatus.OPEN);
        }
    }

    /**
     * Obstruction and overload come and go while the doors are open. A held car (recalled for fire
     * or going out of service) only clears them, so it cannot stay stuck with its doors open.
     */
    private void randomFaults(Car c, boolean mayStart) {
        if (c.door != DoorStatus.OPEN) return;
        if ((mayStart || c.obstruction) && rnd.nextInt(12) == 0) {
            c.obstruction = !c.obstruction;
            api.setObstruction(c.id, c.obstruction);
        }
        if ((mayStart || c.overload) && rnd.nextInt(15) == 0) {
            c.overload = !c.overload;
            api.setOverload(c.id, c.overload);
        }
    }
}
