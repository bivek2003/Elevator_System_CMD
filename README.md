# Team 5 Command Center

A JavaFX panel monitors four elevators serving five floors. Test 1 and Test 2 are
separate programs that supply simulated elevator status through Team 3's original
Software Bus. All copied upstream Java files are unchanged.

## Architecture

```text
Process 1                    Process 2                 Process 3
Command Center               Test 1                    Test 2
     |                           |                         |
SB (port 6000)               SB1 (port 6001)            SB2 (port 6002)
     |                           |                         |
     +---------------------------+-------------------------+
                                 |
                     Team 3 Virtual Router (5555)
                     separate test infrastructure process
```

Each subsystem includes its own bus in the same JVM. The upstream RouterLink
connects each bus to the Virtual Router. Applications connect to their bus's local
port, never to the router port. There are three subsystem processes plus the
router process during isolated distributed testing.

| Program | Entry point | Responsibility |
| --- | --- | --- |
| Panel + SB | `commandcenter.Main` | Display status and send operator commands |
| Test 1 + SB1 | `harness.Test1` | Simulate elevators 1–2 and publish global fire status |
| Test 2 + SB2 | `harness.Test2` | Simulate elevators 3–4 and follow global fire messages |

## Run locally

Use Maven and a JDK supported by the JavaFX version in `pom.xml`. The compiler
target is Java 21. Run these commands from `command-center/`.

Compile, then start Team 3's original router first, in terminal 1:

```sh
mvn test-compile
java -cp target/classes:target/test-classes VirtualRouter.VRMain 5555
```

The unchanged router sources are included only under test sources for isolated
testing, as described in Team 3's guide. For system integration use the bus team's
provided router/service and address.

Terminal 2 starts the panel and SB:

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run
```

Terminal 3 starts Test 1 and SB1:

```sh
java -cp target/classes harness.Test1
```

Terminal 4 starts Test 2 and SB2:

```sh
java -cp target/classes harness.Test2
```

Each application defaults to a router at `localhost:5555`. The local bus ports are
6000, 6001, and 6002 respectively. Close the panel or use Ctrl+C to stop a harness.
The upstream bus has no stop API; its threads end when the owning process exits.
Stopping one harness leaves the panel and other harness running.

## Other addresses and ports

All three applications accept `[routerHost routerPort [localBusPort]]`:

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="192.168.8.10 5555 6000"
java -cp target/classes harness.Test1 192.168.8.10 5555 6001
java -cp target/classes harness.Test2 192.168.8.10 5555 6002
```

To connect to an existing bus instead of starting a local one, use
`--connect busHost busPort`:

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="--connect 192.168.8.10 6000"
java -cp target/classes harness.Test1 --connect 192.168.8.11 6001
java -cp target/classes harness.Test2 --connect 192.168.8.12 6002
```

The old direct SB1--SB--SB2 implementation and its BusNode/BusLink classes have
been removed. The upstream bus/router now handles distributed delivery.

## Our code and upstream code

- `integration/BusConnection.java` is our startup adapter. It invokes the original
  `SoftwareBus.SBMain` in a background thread, or returns an existing bus address.
- `commandcenter/Main.java` starts the panel and connects its manager to SB.
- `commandcenter/SoftwareBusManager.java` sends commands and collects status using
  the upstream REGISTER, SUBSCRIBE, and GET protocol.
- `harness/Test1.java` and `Test2.java` define elevator ownership and local ports.
- `harness/HarnessMain.java` starts each harness and its bus through our adapter.
- `harness/TestManager.java` simulates elevator movement, doors, and service state.
- `harness/BusTestController.java` receives commands and publishes simulated status.
- `SoftwareBus/` and `Utilities/` are unchanged upstream sources. Do not edit them.
- `src/test/java/VirtualRouter/` contains the unchanged upstream test router.

For example, an elevator 4 service command follows this path:

```text
Panel -> SB -> Virtual Router -> SB2 -> Test 2
Panel <- SB <- Virtual Router <- SB2 <- Test 2 confirmation
```

The panel's service button stays pending until the requested state arrives.
The simulator confirms out of service after reaching floor 1 and opening its doors.
Each harness publishes its latest status once a second. Only Test 1 publishes
global fire status, avoiding competing global snapshots.

## Verification

```sh
mvn test-compile
shasum -a 256 -c third-party/software-bus/SHA256SUMS
java -cp target/classes:target/test-classes commandcenter.SoftwareBusIntegrationCheck
java -cp target/classes:target/test-classes commandcenter.DistributedBusCheck
```

The source hashes were checked against upstream commit
`dcc3948d101a8294c71e93eb9737a214ab39d480`, including all copied bus, utility, and
router Java files and the original license.

The standalone checks are compiled by Maven but their `main` methods must be run
explicitly. The local check verifies commands, status, and harness ownership.
The distributed check starts the original Virtual Router in a child process,
runs the panel-side bus/manager without JavaFX in the parent, and launches the
real Test1 and Test2 programs in two more child JVMs. It checks both remote status
sources, subscribe/unsubscribe, fire test/reset, service acknowledgment/return,
independent Test 2 shutdown, and Test 2 restart. Logs go into a printed temporary
directory. These checks do not verify separate physical computers or UI appearance.

## Upstream limitations and remaining work

The upstream implementation can print exceptions on client disconnect and has no
bus shutdown API. RouterLink currently makes one router connection attempt,
despite its comment mentioning reconnection. Start the router first. If that
connection fails or drops, restart the affected subsystem after the router is
ready. Our manager/controller retry their local client connections and restore
subscriptions, but this does not repair a disconnected bus-to-router link.
These upstream behaviors are preserved rather than patched.

FIRE 0 = reset, 1 = alarm, 2 = test; MODE 10 = in service and 11 = out of service
remain provisional application codes. Confirm them with the other teams, along
with command/status semantics and the global fire-status authority. Obstruction
and overload topics are unconfirmed and their bus integration remains pending.
The simulator currently handles alarm input as a fire test; a distinct alarm
scenario remains pending. The panel can display separate test and alarm statuses.
Record final integration results and update the CCR with the screenshot and
architecture.

Upstream source, integration guide, pinned revision, and original license are
recorded in `third-party/software-bus/README.md`. The upstream project is
[Team 3's Software Bus](https://github.com/Wallyhoo/CS460_T03_ElevatorProject).
