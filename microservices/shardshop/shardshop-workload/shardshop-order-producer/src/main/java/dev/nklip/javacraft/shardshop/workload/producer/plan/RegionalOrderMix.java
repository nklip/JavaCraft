package dev.nklip.javacraft.shardshop.workload.producer.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable schedule: exact regional weights, spread across the cycle rather than emitted in blocks. */
final class RegionalOrderMix {
    private static final List<String> CURRENCIES = List.of("USD", "EUR", "GBP", "CNY", "KRW", "JPY", "VND");
    private static final int ORDERS_PER_REGION = 900;

    private final List<Selection> cycle;
    private final Map<String, Integer> currencyCounts;

    RegionalOrderMix(List<String> regions) {
        Map<String, List<String>> schedules = new HashMap<>();
        for (String region : regions) {
            // Home-region share is 90%; each other region gets 5%. Foreign ASIA preserves 6:1:1:1.
            int[] weights = switch (region) {
                case "US" -> new int[]{810, 36, 9, 30, 5, 5, 5};
                case "EU" -> new int[]{45, 720, 90, 30, 5, 5, 5};
                case "ASIA" -> new int[]{45, 36, 9, 540, 90, 90, 90};
                default -> throw new IllegalArgumentException("Unsupported buyer region");
            };
            schedules.put(region, spread(weights));
        }
        List<Selection> selections = new ArrayList<>(ORDERS_PER_REGION * regions.size());
        Map<String, Integer> counts = new HashMap<>();
        for (int ordinal = 0; ordinal < ORDERS_PER_REGION; ordinal++) {
            for (String region : regions) {
                String currency = schedules.get(region).get(ordinal);
                int occurrence = counts.getOrDefault(currency, 0);
                selections.add(new Selection(region, currency, occurrence));
                counts.put(currency, occurrence + 1);
            }
        }
        cycle = List.copyOf(selections);
        currencyCounts = Map.copyOf(counts);
    }

    Selection select(long ordinal) {
        Selection selection = cycle.get((int) (ordinal % cycle.size()));
        // This is the number of preceding orders in this currency, so it cannot exceed ordinal.
        long currencyOrdinal = (ordinal / cycle.size()) * currencyCounts.get(selection.currency())
                + selection.currencyOrdinal();
        return new Selection(selection.region(), selection.currency(), currencyOrdinal);
    }

    private static List<String> spread(int[] weights) {
        int[] current = new int[weights.length];
        List<String> result = new ArrayList<>(ORDERS_PER_REGION);
        for (int ordinal = 0; ordinal < ORDERS_PER_REGION; ordinal++) {
            int selected = 0;
            for (int currency = 0; currency < weights.length; currency++) {
                current[currency] += weights[currency];
                if (current[currency] > current[selected]) {
                    selected = currency;
                }
            }
            current[selected] -= ORDERS_PER_REGION;
            result.add(CURRENCIES.get(selected));
        }
        return List.copyOf(result);
    }

    record Selection(String region, String currency, long currencyOrdinal) { }
}
