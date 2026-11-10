package SoftwareBus;

public class Topics {

    private Topics() {}

    // subtopics
    //Subtopic 0: all elevators. On a subscription it means "any elevator"
    public static final int ALL = 0;
    public static final int ELEVATOR_1 = 1;
    public static final int ELEVATOR_2 = 2;
    public static final int ELEVATOR_3 = 3;
    public static final int ELEVATOR_4 = 4;

    // 0 is reserved / invalid so an all-zero message is easy to spot as a bug.
    // TODO: Need to create large table and standardize messages with all groups
    //Body: 1 = fire alarm on, 0 = reset
    public static final int FIRE = 1;
    // Body: operating mode code (codes TBD by Building Control team)
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
