package commandcenter;

/**
 * Incoming status updates: a manager calls these to update the screen.
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

    /** Register the receiver of operator commands. */
    void setCommandListener(CommandListener listener);
}
