package commandcenter;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Simulates four elevators and drives the Command Center through its API only. */
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
    private final Car[] cars = new Car[CommandCenterApi.ELEVATORS];
    private CommandCenterApi api;
    private ScheduledExecutorService exec;
    private FireStatus fire = FireStatus.NORMAL;

    @Override
    public synchronized void start(CommandCenterApi api) {
        this.api = api;
        int[] startFloors = {3, 1, 5, 2};
        for (int i = 0; i < cars.length; i++) {
            Car c = cars[i] = new Car(i + 1, startFloors[i]);
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
    @Override
    public synchronized void onFireTest() {
        fire = FireStatus.TEST;
        api.setFireStatus(fire);
        for (Car c : cars) recall(c);
    }

    @Override
    public synchronized void onFireReset() {
        fire = FireStatus.NORMAL;
        api.setFireStatus(fire);
    }

    /**
     * Taking out of service does not stop the elevator: it travels to the bottom floor and opens
     * its doors, and only then is reported out of service.
     */
    @Override
    public synchronized void onServiceCommand(int id, ServiceStatus target) {
        Car c = cars[id - 1];
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
            for (Car c : cars) step(c);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private boolean held(Car c) { return !c.inService || fire != FireStatus.NORMAL; }

    private void step(Car c) {
        if (c.inService && fire == FireStatus.NORMAL) randomFaults(c);

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

    private void randomFaults(Car c) {
        if (c.door == DoorStatus.OPEN && rnd.nextInt(12) == 0) {
            c.obstruction = !c.obstruction;
            api.setObstruction(c.id, c.obstruction);
        }
        if (c.door == DoorStatus.OPEN && rnd.nextInt(15) == 0) {
            c.overload = !c.overload;
            api.setOverload(c.id, c.overload);
        }
    }
}
