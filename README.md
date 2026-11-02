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

The demo uses temporary codes: FIRE 0 = reset, FIRE 2 = test,
MODE 10 = in service, and MODE 11 = out of service.
These still need agreement between teams.

## Other run options

Simulation without the bus:

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run -Djavafx.args="--demo"
```

UI with a local bus, without the test controller:

```sh
mvn org.openjfx:javafx-maven-plugin:0.0.8:run
```

This needs a separate controller to send elevator updates. Service commands also
need `bus.inServiceCode` and `bus.outOfServiceCode` set as JVM properties.

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
