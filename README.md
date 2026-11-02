# Team 5 Command Center

Run from `command-center/`: `mvn org.openjfx:javafx-maven-plugin:0.0.8:run`  (needs JDK 21+ and Maven; JavaFX 25.0.1 is downloaded on first run).
Use the full plugin coordinates: the short `mvn javafx:run` fails if Maven cannot resolve the plugin prefix (e.g. a 401 from a corporate mirror in ~/.m2/settings.xml).

## Java controller through the software bus

From the repository root:

```sh
cd command-center
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="--bus-demo"
```

This starts the UI, the actual local software bus on port 6000, and `BusTestController`.
The UI and controller have separate TCP connections. All commands and status updates travel
through the bus; the controller reuses `TestManager` for elevator movement without accessing the UI.
Close any previous bus-mode UI using port 6000 before starting this demo.

For class, show the moving floor displays, press Fire Test to recall all cars to floor 1 with doors
open, then press Fire Reset to resume normal operation. Take one car out of service: its button
stays pending until it reaches floor 1, opens its doors, and confirms OUT_OF_SERVICE. Return it
to service to resume movement. Terminal output shows commands received by the Java controller.

Demo-only body values are MODE 10 = in service, MODE 11 = out of service, FIRE 2 = test,
and FIRE 0 = reset. These are temporary classroom values, not agreed team standards. `--bus-demo`
sets both ends to these values automatically; ordinary bus mode keeps its existing configuration.
Obstruction/overload faults are disabled in the bus controller because upstream has no topics for them.

Flow: operator button → API polling → UI bus connection → software bus → controller GET polling →
simulation → controller status publication → software bus → UI GET polling → display.

The controller also supports a separate terminal after compiling with `mvn test-compile`:

```sh
java -cp target/classes commandcenter.BusTestController localhost 6000
```

Use a UI configured with the same demo codes (JVM properties `bus.inServiceCode=10`,
`bus.outOfServiceCode=11`, `bus.fireTestCode=2`). Do not run a second controller alongside `--bus-demo`.

## Screen

One grid. Top row, one box per column: Fire Reset, Fire Test, a blank box and the Fire Alarm indicator.
Below it, one column per elevator: name, Take out of service / Return to service button, up arrow,
floor display, down arrow, then five stacked boxes: direction, a blank separator, door, obstruction,
overload. The column of an out-of-service elevator is dimmed.
Removed per class feedback: system ON/OFF, Go To floor, floor-reset/auto-door table.

Colours (all in `command-center/src/main/resources/commandcenter/command-center.css`) each keep one meaning:

- Green: moving (Up/Down box). A green button brings things back to normal (Return to service,
  Fire Reset during a test or alarm).
- Amber: caution (obstruction, overload, Fire Alarm box during a fire test). An amber button is still in
  progress (Fire Test while the test runs, a service command until the manager reports the new status).
- Red: fire alarm (Fire Alarm box).

## Architecture

- `CommandCenterApi`  – status updates in (setCurrentFloor, setDirection, setDoorStatus, setObstruction,
  setOverload, setServiceStatus, setFireStatus) and boolean polling for operator actions
  (fireReset, fireTest, takeOutOfService(elevatorId), returnToService(elevatorId)). Thread-safe.
  Each poll atomically returns and clears its own flag: a press returns true once, then false
  until another press. Repeated presses before polling combine into one action.
- `Manager`           – replaceable back end that polls the API. `TestManager` polls once per second
  and simulates the elevators. If test and reset are both pending, reset takes priority.
- `SoftwareBusManager` – the default back end: polls operator actions and the bus every 100 ms on
  a background thread, publishes commands, and applies received status updates to the API.
- `Main`              – starts an embedded bus on localhost:6000 by default and connects the manager.
  Passing a host and port connects to an existing bus instead. `--demo` selects `TestManager`.
  `--bus-demo` adds a Java test controller on a separate connection to the real local bus.

Polling a service action clears its command flag; the button stays pending until the manager
reports the new state with `setServiceStatus`. Polling fire actions does not change the fire
indicator; the manager reports the result with `setFireStatus`.

In the demo, out of service sends the elevator to the bottom floor and opens its doors before
reporting out of service. With the bus, the elevator controller is responsible for this behavior.

## Software bus

Uses the bus from [CS460_T03_ElevatorProject](https://github.com/Wallyhoo/CS460_T03_ElevatorProject),
commit `e92a8508a9fe6d86fb86c07dc4dc5122def21cfe`. The shared Utilities and SoftwareBus sources are
included unchanged; provenance and the MIT license are in `command-center/third-party/software-bus/`.

Run with an existing bus (from `command-center/`):

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="192.168.8.10 6000"
```

Run the simulation without a bus (from `command-center/`):

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="--demo"
```

The manager uses the upstream `Message` and `Protocol` classes for 8-byte frames and
SUBSCRIBE/GET/NO_MESSAGE. It subscribes to each elevator separately, including ALL for broadcasts,
because the current bus's ALL subscription does not receive per-elevator publications.
It reconnects and resubscribes on socket errors. Unsent commands are retained for retry; the
protocol has no application acknowledgement, so delivery across disconnects is not guaranteed
and a failed write can cause a duplicate on retry. Statuses missed while disconnected are not replayed.

| Action/status       | Topic         | Subtopic           | Body                                   |
| ------------------- | ------------- | ------------------ | -------------------------------------- |
| Fire reset          | FIRE          | ALL                | 0                                      |
| Fire test           | FIRE          | ALL                | 1 by default (same recall as an alarm) |
| Take out of service | MODE          | elevator ID        | configured out-of-service code         |
| Return to service   | MODE          | elevator ID        | configured in-service code             |
| Current floor       | CAR_POSITION  | elevator ID or ALL | 1–5                                   |
| Direction           | CAR_DIRECTION | elevator ID or ALL | 0 stopped, 1 up, 2 down                |
| Door                | DOOR_STATE    | elevator ID or ALL | 0 closed, 2 open                       |

The upstream MODE values are explicitly TBD. Set `bus.inServiceCode` and `bus.outOfServiceCode`
to the Building Control team's agreed values as JVM system properties (for Maven's JavaFX launch,
include `-Dbus.inServiceCode=... -Dbus.outOfServiceCode=...` in `JDK_JAVA_OPTIONS`). Without both
codes, service actions remain pending and are not consumed or published; startup prints an explanation.
Unknown MODE values are ignored. A received service status, rather than a successful send, clears
the service button's pending indicator.

The bus defines FIRE 0 = reset and 1 = alarm, with no separate test code. The default fire-test
command therefore activates the same fire recall as an alarm; received FIRE 1 displays ALARM.
Set `bus.fireTestCode` only if the teams agree on a separate code; a distinct received test code
displays TEST. Sending a command does not confirm status: another controller must publish the result,
because the bus does not echo the sender's messages.

There are no upstream obstruction or overload topics yet, so these indicators still require API
updates from an integration that defines those messages. Opening/closing door values leave the last
open/closed display unchanged because the UI currently represents only those two states.
The embedded upstream bus has no stop API and runs on daemon threads until the application exits.

Run the standalone integration check from `command-center/` (uses a temporary local TCP port):

```sh
mvn test-compile
java -cp target/classes:target/test-classes commandcenter.SoftwareBusIntegrationCheck
```

It checks command publication and consumption, status updates, broadcast delivery, invalid telemetry,
and service confirmation against the actual upstream bus. The upstream server prints disconnect
stack traces when test sockets close; this is a known upstream issue.
