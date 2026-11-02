# Team 5 Command Center (JavaFX demo)

Run: `mvn org.openjfx:javafx-maven-plugin:0.0.8:run`  (needs JDK 21+ and Maven; JavaFX 25.0.1 is downloaded on first run).
Use the full plugin coordinates: the short `mvn javafx:run` fails if Maven cannot resolve the plugin prefix (e.g. a 401 from a corporate mirror in ~/.m2/settings.xml).

## Screen
One grid. Top row, one box per column: Fire Reset, Fire Test, a blank box and the Fire Alarm indicator.
Below it, one column per elevator: name, Take out of service / Return to service button, up arrow,
floor display, down arrow, then five stacked boxes: direction, a blank separator, door, obstruction,
overload. The column of an out-of-service elevator is dimmed.
Removed per class feedback: system ON/OFF, Go To floor, floor-reset/auto-door table.

Colours (all in `src/main/resources/commandcenter/command-center.css`) each keep one meaning:
- Green: moving (Up/Down box). A green button brings things back to normal (Return to service,
  Fire Reset during a test or alarm).
- Amber: caution (obstruction, overload, Fire Alarm box during a fire test). An amber button is still in
  progress (Fire Test while the test runs, a service command until the manager reports the new status).
- Red: fire alarm (Fire Alarm box).

## Architecture
- `CommandCenterApi`  – status updates in (setCurrentFloor, setDirection, setDoorStatus, setObstruction,
  setOverload, setServiceStatus, setFireStatus). Thread-safe.
- `CommandListener`   – operator commands out (onFireTest, onFireReset, onServiceCommand).
- `Manager`           – replaceable back end. `TestManager` simulates the elevators.
- `Main`              – wires view and manager. To use a Software Bus manager, implement `Manager`
  and change only `Main.createManager()`; the view and API are untouched.

Out of service: the elevator is sent to the bottom floor, opens its doors, then reports out of service.
