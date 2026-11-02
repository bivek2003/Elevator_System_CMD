package commandcenter;

/**
 * Replaceable back end of the Command Center. It pushes status into the
 * {@link CommandCenterApi} and polls it for operator commands. A software bus
 * implementation publishes consumed commands to the bus. The UI never depends
 * on a concrete manager.
 */
public interface Manager {
    void start(CommandCenterApi api);
    void stop();
}
