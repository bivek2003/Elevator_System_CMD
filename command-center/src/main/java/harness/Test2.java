package harness;

import java.util.Set;

/** Process 3: Test 2 owns elevators 3 and 4 and runs its own bus, SB2. */
public class Test2 {
    public static void main(String[] args) throws Exception {
        HarnessMain.start("Test 2 / SB2", 6002, Set.of(3, 4), false, args);
    }
}
