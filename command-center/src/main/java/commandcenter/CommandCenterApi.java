package commandcenter;

/**
 * Status updates and polling for operator actions.
 * Implementations must be safe to call from any thread.
 * Elevator ids are 1..{@link #ELEVATORS}.
 */
public interface CommandCenterApi {
    int ELEVATORS = 4;
    int FLOORS = 5;

    void setCurrentFloor(int elevatorId, int floor);
    void setDirection(int elevatorId, Direction direction);
    void setDoorStatus(int elevatorId, DoorStatus status);
    void setObstruction(int elevatorId, boolean active);
    void setOverload(int elevatorId, boolean active);
    void setServiceStatus(int elevatorId, ServiceStatus status);
    void setFireStatus(FireStatus status);

    /**
     * Poll operator actions. Each method atomically returns and clears its pending flag:
     * true once after a press, then false until the next press. Repeated presses before
     * a poll are combined into one pending action. Polling does not change reported status.
     * A single manager consumes these actions and forwards them to the software bus.
     */
    boolean fireReset();
    boolean fireTest();
    boolean takeOutOfService(int elevatorId);
    boolean returnToService(int elevatorId);
}
