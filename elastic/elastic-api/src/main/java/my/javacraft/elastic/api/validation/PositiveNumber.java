package my.javacraft.elastic.api.validation;

// Utility class with static methods
public final class PositiveNumber {

    public static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    public static long positiveOrDefault(long value, long defaultValue) {
        return value > 0 ? value : defaultValue;
    }
}
