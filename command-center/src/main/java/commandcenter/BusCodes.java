package commandcenter;

/** Message body codes from Software_Bus_Codes.xlsx, shared by the UI and test controller. */
public final class BusCodes {
    private BusCodes() { }

    public static final long IN_SERVICE = 10;
    public static final long OUT_OF_SERVICE = 11;
    public static final long FIRE_TEST = 2;
}
