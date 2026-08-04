package dev.nklip.javacraft.weather.domain;

import java.util.List;

/** The three cities this showcase reports on. */
public final class Cities {

    public static final City GLASGOW = new City("Glasgow", "United Kingdom", 55.8642, -4.2518);
    public static final City SAMARA = new City("Samara", "Russia", 53.2001, 50.1500);
    public static final City NHA_TRANG = new City("Nha Trang", "Vietnam", 12.2388, 109.1967);

    private static final List<City> ALL = List.of(GLASGOW, SAMARA, NHA_TRANG);

    private Cities() {
    }

    /** Immutable list, in the order the dashboard renders them. */
    public static List<City> all() {
        return ALL;
    }
}
