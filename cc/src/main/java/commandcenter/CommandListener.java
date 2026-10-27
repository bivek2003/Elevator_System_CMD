package commandcenter;

/** Operator commands raised by the Command Center screen. */
public interface CommandListener {
    void onFireTest();
    void onFireReset();
    /** @param target IN_SERVICE = return to service, OUT_OF_SERVICE = take out of service */
    void onServiceCommand(int elevatorId, ServiceStatus target);
}
