package commandcenter;

/**
 * Replaceable back end of the Command Center. It pushes status into the
 * {@link CommandCenterApi} and receives operator commands as the
 * {@link CommandListener}. The UI never depends on a concrete manager.
 */
public interface Manager extends CommandListener {
    void start(CommandCenterApi api);
    void stop();
}
