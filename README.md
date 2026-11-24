# Team 5 Command Center

A JavaFX panel monitors four elevators serving five floors. Test 1 and Test 2 are
separate programs that supply simulated elevator status through Team 3's original
Software Bus. All copied upstream Java files are unchanged.
The pinned bus revision is `44402531af830541001fc74b478beeeb62c95def`.

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

Compile, then start Team 3's router in terminal 1:

```sh
mvn clean test-compile
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
After 15 seconds without matching confirmation, it allows a retry of the same
request. A stale connection also allows retry once fresh service status returns.
Retry does not change the reported service state or invent a confirmation.
The simulator confirms out of service after reaching floor 1 and opening its doors.
Each harness publishes its latest status once a second. Only Test 1 publishes
global fire status, avoiding competing global snapshots.

Both clients read and validate REGISTER's assigned-ID reply before subscribing.
They use one wildcard subscription per topic, with one worker pairing each GET
with its reply. The harness filters commands by its owned elevator IDs.

Floor/direction, door/warnings, service, and fire each expire separately after
five seconds without a valid status message, or immediately on a lost client
connection. The panel starts with unknown status and disables service controls
until fresh service status arrives. Stale floor displays show a dash; stale
door and warning labels say Stale. An active fire alarm remains visible with a
stale label. Neither door limit means Transition; contradictory door limits
are ignored. The building still has five floors, even though the catalog
allows floor numbers up to ten.

## Verification

```sh
mvn clean test-compile
shasum -a 256 -c third-party/software-bus/SHA256SUMS
java -cp target/classes:target/test-classes commandcenter.SoftwareBusIntegrationCheck
java -cp target/classes:target/test-classes commandcenter.DistributedBusCheck
```

The source hashes were checked against upstream commit
`44402531af830541001fc74b478beeeb62c95def`, including all copied bus, utility, and
router Java files and the original license.

The standalone checks are compiled by Maven but their `main` methods must be run
explicitly. The local check verifies registration replies and repeated IDs,
single wildcard delivery, commands, valid/invalid status, door transitions,
warnings, stale status, harness ownership, and both clients reconnecting to a
restarted bus.
The distributed check starts the original Virtual Router in a child process,
runs the panel-side bus/manager without JavaFX in the parent, and launches the
real Test1 and Test2 programs in two more child JVMs. It checks both remote status
sources, subscribe/unsubscribe, fire test/reset, service acknowledgment/return,
independent Test 2 shutdown, and Test 2 restart. It also starts buses before the
router, restarts the router, and checks that only stopped sources become stale.
Logs go into a printed temporary directory. These checks use separate JVMs on
one computer.

`commandcenter.CommandCenterViewCheck` checks the JavaFX buttons, pending service
confirmation, unknown/stale displays, and alarm retention. It captures sample
screens in `command-center/docs/`. To run it on Apple Silicon after compilation:

```sh
cs460_fx="$HOME/.m2/repository/org/openjfx"
java --enable-native-access=javafx.graphics \
  --module-path "$cs460_fx/javafx-base/25.0.1/javafx-base-25.0.1-mac-aarch64.jar:$cs460_fx/javafx-graphics/25.0.1/javafx-graphics-25.0.1-mac-aarch64.jar:$cs460_fx/javafx-controls/25.0.1/javafx-controls-25.0.1-mac-aarch64.jar" \
  --add-modules javafx.controls -cp target/classes:target/test-classes \
  commandcenter.CommandCenterViewCheck docs
```

Compile, source checksums, local/distributed checks, and the JavaFX check passed
on 23 November 2026. See [integration status](command-center/docs/integration-status.md)
for the screenshot and agreements still needed. Generated `target/` files are
ignored and are no longer tracked.

## Upstream limitations and remaining work

The upstream implementation prints exceptions on client disconnect and has no
bus-server shutdown API. Its RouterLink retries failed/dropped connections every
two seconds. It drops publications while disconnected and clears its outgoing
queue when reconnecting. A successful write to a local bus does not confirm
remote delivery or completion. Harness snapshots restore current telemetry
after reconnecting; an operator may need to reissue an unconfirmed command.
Our manager/controller reconnect to their local bus, register again, and restore
their subscriptions. These upstream behaviors are preserved.

Message layouts follow the Team 3 Software Bus Message Catalog (08 Nov 2026), via
`Utilities.Message` and `Utilities.Topics` from Team 3's repo: byte 0 is the topic,
byte 1 the subtopic (elevator 1-4, or 0 for building-wide), and catalog "payload
byte n" is read with `payload(n)`. The Command Center publishes FIRE (1 = test fire,
0 = reset) and ELEVATOR_COMMAND (1 = in service, 0 = out of service), and consumes
FIRE, ELEVATOR_COMMAND, CABIN_STATUS (floor, direction) and DOOR_STATUS (open/closed,
obstruction, overweight). Build catalog messages with the named `Message`
builders. Use `Message.of(...)` only for bus commands. The catalog has
a single fire value, so an incoming FIRE = 1 is shown as ALARM; confirm the
global fire-status authority with the other teams.
The simulator currently handles alarm input as a fire test; a distinct alarm
scenario remains pending. The panel can display separate test and alarm statuses.
Record final integration results and update the CCR with the screenshot and
architecture.

The catalog defines ELEVATOR_COMMAND as an outgoing command, with no separate
service-status confirmation. Our test controller republishes that topic as
confirmation and periodic service status. This is a demo convention requiring
agreement with the actual controller teams. Another operator's command cannot
be distinguished from a controller confirmation by this wire format. Do not
claim final hardware integration until service feedback and fire semantics are
agreed and tested across the real machines.

Upstream source, integration guide, pinned revision, and original license are
recorded in `third-party/software-bus/README.md`. The upstream project is
[Team 3's Software Bus](https://github.com/Wallyhoo/CS460_T03_ElevatorProject).
