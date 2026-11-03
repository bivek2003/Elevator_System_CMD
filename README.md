# Team 5 Command Center

A JavaFX command center for four elevators. It shows floor, direction, door status,
and warnings, with buttons for fire test/reset and taking elevators out of service.

Requires JDK 21+ and Maven. Run the commands below from `command-center/`.

## Run with the software bus demo

```sh
cd command-center
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="--bus-demo"
```

This starts the UI, a local bus on port 6000, and a Java test controller.
The elevators move, and the fire and service buttons work through the bus.
Close any other bus using port 6000 first.

The UI and test controller use the codes in `Software_Bus_Codes.xlsx`:
FIRE 0 = reset/normal, FIRE 1 = alarm, FIRE 2 = test,
MODE 10 = in service, and MODE 11 = out of service.
The controller confirms out of service after reaching floor 1 and opening its doors.
Until then, the last reported service status remains displayed. A separate
"going out of service" code is pending agreement and is not implemented.

## Other run options

Simulation without the bus:

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="--demo"
```

UI with a local bus, without the test controller:

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run
```

This needs a separate controller to send elevator updates. The spreadsheet codes
are used by default. JVM properties `bus.inServiceCode`, `bus.outOfServiceCode`,
and `bus.fireTestCode` can override them for another controller.

Connect to an existing bus (replace the example IP):

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="192.168.8.10 6000"
```

## API

Operator actions use polling. Methods like `fireReset()` return true once after
a button press, then false until the next press. The manager sends the commands
to the bus and updates the UI from received status messages.

The software bus comes from [Team 3's repository](https://github.com/Wallyhoo/CS460_T03_ElevatorProject).
Its license is in `command-center/third-party/software-bus/`.
