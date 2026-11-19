package Utilities;

/**
 * Topic numbers for every message in the approved Software Bus Message Catalog
 * (Team #3, 08 November 2026), plus subtopic helpers.
 *
 * Byte numbering follows the catalog: "payload byte 1" is the first byte after the
 * subtopic, i.e. wire index 2. See {@link Message} for the full layout
 * and the factory methods that build each message.
 *
 * Topic numbers 1, 2, 3, 5, 6 and 7 keep the numbers of the topics they replace.
 * Topic 4 (the old CAR_DIRECTION) is retired: direction is now part of CABIN_STATUS,
 * and leaving 4 unused means a stray old-style message can't be misread as a new one.
 * Values 0xF0 and up are reserved for bus commands (see {@link Protocol}).
 */

public class Topics {

    private Topics() {}

    // subtopics
    // Subtopic 0: "none" for building-wide messages (hall calls, FIRE, CLEAR_REQUEST).
    //Subtopic 0: all elevators. On a subscription it means "any elevator"
    public static final int ALL = 0;
    public static final int NONE = 0;
    public static final int ELEVATOR_1 = 1;
    public static final int ELEVATOR_2 = 2;
    public static final int ELEVATOR_3 = 3;
    public static final int ELEVATOR_4 = 4;

    // 0 is reserved / invalid so an all-zero message is easy to spot as a bug.
    /** Hallway Floor Panel & Command Center, subtopic 0. P1: 1 = fire alert / test fire, 0 = reset. */
    public static final int FIRE = 1;                 // unchanged
    /** Command Center, subtopic elevator. P1: 0 = out of service, 1 = in service. */
    public static final int ELEVATOR_COMMAND = 2;     // was MODE
    /** Cabin, subtopic elevator. P1: stopped (1 = stopped), P2: floor 1-10, P3: dir 0 idle/1 up/2 down. */
    public static final int CABIN_STATUS = 3;         // was CAR_POSITION
    // 4 retired (was CAR_DIRECTION, now folded into CABIN_STATUS)
    /** Hallway Floor Panel, subtopic 0. P1: floor 1-10. */
    public static final int UP_CALL = 5;              // was HALL_CALL
    /** Elevator Panel, subtopic elevator. P1: floor 1-10. */
    public static final int CABIN_CALL = 6;           // was CAR_CALL
    /** Door, subtopic elevator. P1: fully open, P2: fully closed, P3: obstructed, P4: overweight (1 = yes). */
    public static final int DOOR_STATUS = 7;          // was DOOR_STATE
    /** Hallway Floor Panel, subtopic 0. P1: floor 1-10. */
    public static final int DOWN_CALL = 8;
    /** Cabin, subtopic elevator. P1: 0 none, 1 bottom, 2 top, 3 both aligned. */
    public static final int ALIGN = 9;
    /** Cabin, subtopic elevator. P1: 0 stop / 1 start, P2: 1 up / 2 down. */
    public static final int MOTOR_COMMAND = 10;
    /** Cabin, subtopic elevator. P1: 0 close / 1 open. */
    public static final int DOOR_COMMAND = 11;
    /** Cabin, subtopic elevator. P1: 0 off / 1 on. */
    public static final int BUZZER = 12;
    /** Cabin, subtopic elevator. P1: floor 1-10, P2: leaving direction 0 none/1 up/2 down. */
    public static final int ARRIVAL_CHIME = 13;
    /** Elevator Panel, subtopic elevator. P1: 0 off / 1 on. */
    public static final int KEY = 14;
    /** Control Center, subtopic elevator. P1: floor 1-10 (keep doors closed on arrival). */
    public static final int GOTO = 15;
    /** Control Center, subtopic elevator. P1: floor 1-10 (open doors on arrival). */
    public static final int SERVICE = 16;
    /** Control Center, subtopic 0. P1: hallway floor 1-10, P2: 1 up / 2 down / 3 both. */
    public static final int CLEAR_REQUEST = 17;
    /** Control Center, subtopic elevator. P1: elevator panel button 1-10. */
    public static final int CLEAR_BUTTONS = 18;

    /** True if the topic is one of the catalog topics above. */
    public static boolean isCatalogTopic(int topic) {
        return !topicName(topic).startsWith("TOPIC_");
    }

    /** True for topics whose subtopic must be 0 (building-wide, not per elevator). */
    public static boolean isBuildingWide(int topic) {
        return topic == FIRE || topic == UP_CALL || topic == DOWN_CALL || topic == CLEAR_REQUEST;
    }

    // Human-readable topic name for terminal outputs
    public static String topicName(int topic) {
        switch (topic) {
            case FIRE:             return "FIRE";
            case ELEVATOR_COMMAND: return "ELEVATOR_COMMAND";
            case CABIN_STATUS:     return "CABIN_STATUS";
            case UP_CALL:          return "UP_CALL";
            case CABIN_CALL:       return "CABIN_CALL";
            case DOOR_STATUS:      return "DOOR_STATUS";
            case DOWN_CALL:        return "DOWN_CALL";
            case ALIGN:            return "ALIGN";
            case MOTOR_COMMAND:    return "MOTOR_COMMAND";
            case DOOR_COMMAND:     return "DOOR_COMMAND";
            case BUZZER:           return "BUZZER";
            case ARRIVAL_CHIME:    return "ARRIVAL_CHIME";
            case KEY:              return "KEY";
            case GOTO:             return "GOTO";
            case SERVICE:          return "SERVICE";
            case CLEAR_REQUEST:    return "CLEAR_REQUEST";
            case CLEAR_BUTTONS:    return "CLEAR_BUTTONS";
            default:               return "TOPIC_" + topic;
        }
    }

    // Human-readable subtopic name for terminal outputs
    public static String subtopicName(int subtopic) {
        return subtopic == ALL ? "ALL" : "ELEV_" + subtopic;
    }
}
