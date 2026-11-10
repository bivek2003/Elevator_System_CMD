package harness;

import java.util.Set;

/** Process 2: Test 1 owns elevators 1 and 2 and runs its own bus, SB1. */
public class Test1 {
    public static void main(String[] args) throws Exception {
        HarnessMain.start("Test 1 / SB1", 6001, Set.of(1, 2), true, args);
    }
}
