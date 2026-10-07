package dev.nklip.javacraft.shardshop.workload.dataset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable workload definitions; keys identify retries, while order creates entity IDs. */
public final class BuyerDataset {

    public static final String VERSION = "buyers-v1";
    private static final List<String> REGIONS = List.of("US", "EU", "ASIA");
    private static final int BUYERS_PER_REGION = 1000;
    private static final String BUYER_HEADER = "firstName\tsurname\temail\tphone\tline1\tcity\tpostalCode\tcountryCode";

    private final List<BuyerDefinition> buyers;
    private final Map<String, List<BuyerDefinition>> buyersByRegion;

    public BuyerDataset(String version) {
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported buyer dataset version");
        }
        List<BuyerDefinition> definitions = new ArrayList<>();
        Map<String, List<BuyerDefinition>> regionalBuyers = new LinkedHashMap<>();
        for (String region : REGIONS) {
            List<List<String>> rows = DatasetResources.load("/datasets/" + VERSION + "/" + region + ".tsv",
                    BUYER_HEADER, BUYERS_PER_REGION);
            List<BuyerDefinition> regionBuyers = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                List<String> row = rows.get(index);
                Address address = new Address(row.get(4), row.get(5), row.get(6), row.get(7));
                regionBuyers.add(new BuyerDefinition(VERSION + "." + region + ".buyer." + (index + 1),
                        row.get(0), row.get(1), row.get(2), row.get(3), address, region));
            }
            regionalBuyers.put(region, List.copyOf(regionBuyers));
            definitions.addAll(regionBuyers);
        }
        buyers = List.copyOf(definitions);
        buyersByRegion = Collections.unmodifiableMap(regionalBuyers);
    }

    public List<BuyerDefinition> buyers() {
        return buyers;
    }

    public Map<String, List<BuyerDefinition>> buyersByRegion() {
        return buyersByRegion;
    }

    public record BuyerDefinition(String key, String firstName, String surname, String email,
                                  String phone, Address address, String region) { }

    public record Address(String line1, String city, String postalCode, String countryCode) { }
}
