package SoftwareBus;

public class Topics {

    private Topics() {}

    // subtopics
    // Subtopic 0: broadcasts to all elevators; a subscription to 0 receives broadcasts only.
    public static final int ALL = 0;
    public static final int ELEVATOR_1 = 1;
    public static final int ELEVATOR_2 = 2;
    public static final int ELEVATOR_3 = 3;
    public static final int ELEVATOR_4 = 4;

    // 0 is reserved / invalid so an all-zero message is easy to spot as a bug.
    // Body codes are documented in Software_Bus_Codes.xlsx.
    // Body: 0 = reset/normal, 1 = fire alarm, 2 = fire test
    public static final int FIRE = 1;
    // Body: 10 = in service, 11 = out of service (after floor 1 arrival and doors open)
    public static final int MODE = 2;
    // Body: current floor of the car named in the subtopic
    public static final int CAR_POSITION = 3;
    // Body: 0 = idle, 1 = up, 2 = down
    public static final int CAR_DIRECTION = 4;
    // Body: floor number of a hall call
    public static final int HALL_CALL = 5;
    // Body: floor number pressed inside the car
    public static final int CAR_CALL = 6;
    // Body: 0 = closed, 1 = opening, 2 = open, 3 = closing
    public static final int DOOR_STATE = 7;

    // Human-readable topic name for terminal outputs
    public static String topicName(int topic) {
        switch (topic) {
            case FIRE:          return "FIRE";
            case MODE:          return "MODE";
            case CAR_POSITION:  return "CAR_POSITION";
            case CAR_DIRECTION: return "CAR_DIRECTION";
            case HALL_CALL:     return "HALL_CALL";
            case CAR_CALL:      return "CAR_CALL";
            case DOOR_STATE:    return "DOOR_STATE";
            default:            return "TOPIC_" + topic;
        }
    }

    // Human-readable subtopic name for terminal outputs
    public static String subtopicName(int subtopic) {
        return subtopic == ALL ? "ALL" : "ELEV_" + subtopic;
    }
}
