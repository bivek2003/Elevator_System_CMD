# Team 5 Command Center (JavaFX demo)

Run: `mvn org.openjfx:javafx-maven-plugin:0.0.8:run`  (needs JDK 21+ and Maven; JavaFX 25.0.1 is downloaded on first run).
Use the full plugin coordinates: the short `mvn javafx:run` fails if Maven cannot resolve the plugin prefix (e.g. a 401 from a corporate mirror in ~/.m2/settings.xml).

## Screen
One GridPane: Fire Test / Fire Reset / fire status on top; below it four aligned elevator columns showing
floor, direction, door open/closed, obstruction, overload, service state and a per-elevator
Take out of service / Return to service button.
Removed per class feedback: system ON/OFF, Go To floor, floor-reset/auto-door table.

## Architecture
- `CommandCenterApi`  – status updates in (setCurrentFloor, setDirection, setDoorStatus, setObstruction,
  setOverload, setServiceStatus, setFireStatus). Thread-safe.
- `CommandListener`   – operator commands out (onFireTest, onFireReset, onServiceCommand).
- `Manager`           – replaceable back end. `TestManager` simulates the elevators.
- `Main`              – wires view and manager. To use a Software Bus manager, implement `Manager`
  and change only `Main.createManager()`; the view and API are untouched.

Out of service: the elevator is sent to the bottom floor, opens its doors, then reports out of service.
