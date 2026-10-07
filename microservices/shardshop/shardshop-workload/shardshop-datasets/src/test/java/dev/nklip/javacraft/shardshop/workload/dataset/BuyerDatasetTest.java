package dev.nklip.javacraft.shardshop.workload.dataset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuyerDatasetTest {
    private static final Map<String, Set<String>> COUNTRIES = Map.of(
            "US", Set.of("US"),
            "EU", Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
                    "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"),
                    "ASIA", Set.of("CN", "VN", "KR", "JP"));

    @Test
    void usesOnlyReservedContactCoordinates() {
        BuyerDataset dataset = new BuyerDataset(BuyerDataset.VERSION);

        for (var buyer : dataset.buyers()) {
            assertTrue(buyer.email().matches("[a-z]+\\.[a-z]+@example\\.(com|net|org)"));
            assertTrue(buyer.phone().matches("\\+120255501[0-9]{2}"));
        }
        assertEquals(100, dataset.buyers().stream().map(BuyerDataset.BuyerDefinition::phone).distinct().count());
    }

    @Test
    void loadsOneThousandCompletePeopleInEachRegion() {
        BuyerDataset dataset = new BuyerDataset("buyers-v1");

        assertEquals("buyers-v1", BuyerDataset.VERSION);
        assertEquals(3000, dataset.buyers().size());
        assertEquals(List.of("US", "EU", "ASIA"), List.copyOf(dataset.buyersByRegion().keySet()));
        assertEquals(3000, dataset.buyers().stream().map(BuyerDataset.BuyerDefinition::key).distinct().count());
        assertEquals(3000, dataset.buyers().stream().map(BuyerDataset.BuyerDefinition::email).distinct().count());
        assertEquals(100, dataset.buyers().stream().map(BuyerDataset.BuyerDefinition::phone).distinct().count());
        for (var group : dataset.buyersByRegion().entrySet()) {
            assertEquals(1000, group.getValue().size());
            for (int index = 0; index < group.getValue().size(); index++) {
                var buyer = group.getValue().get(index);
                assertEquals("buyers-v1." + group.getKey() + ".buyer." + (index + 1), buyer.key());
                assertEquals(group.getKey(), buyer.region());
                assertFalse(buyer.firstName().isBlank());
                assertFalse(buyer.surname().isBlank());
                assertTrue(buyer.email().matches("[a-z]+\\.[a-z]+@example\\.(com|net|org)"));
                assertTrue(buyer.email().indexOf('@') <= 64);
                assertTrue(buyer.email().length() <= 254);
                assertTrue(buyer.phone().matches("\\+[0-9]{7,15}"));
                assertFalse(buyer.address().line1().isBlank());
                assertFalse(buyer.address().city().isBlank());
                assertFalse(buyer.address().postalCode().isBlank());
                assertTrue(buyer.address().line1().codePointCount(0, buyer.address().line1().length()) <= 200);
                assertTrue(buyer.address().city().codePointCount(0, buyer.address().city().length()) <= 100);
                assertTrue(buyer.address().postalCode().codePointCount(0, buyer.address().postalCode().length()) <= 20);
                assertEquals(2, buyer.address().countryCode().length());
                assertTrue(COUNTRIES.get(group.getKey()).contains(buyer.address().countryCode()));
            }
        }
        assertEquals(COUNTRIES.get("ASIA"), Set.copyOf(dataset.buyersByRegion().get("ASIA").stream()
                .map(buyer -> buyer.address().countryCode()).toList()));
        assertEquals(dataset.buyers(), dataset.buyersByRegion().values().stream().flatMap(List::stream).toList());
    }

    @Test
    void recreatesTheSameImmutableListsAndNestedMapsAfterRestart() {
        BuyerDataset first = new BuyerDataset("buyers-v1");
        BuyerDataset restarted = new BuyerDataset("buyers-v1");

        assertEquals(first.buyers(), restarted.buyers());
        assertEquals(first.buyersByRegion(), restarted.buyersByRegion());
        assertThrows(UnsupportedOperationException.class, () -> first.buyers().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.buyersByRegion().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.buyersByRegion().get("ASIA").clear());
    }

    @Test
    void rejectsUnknownMissingAndNoncanonicalVersions() {
        for (String version : new String[] { "buyers-v2", "catalog-v1", "BUYERS-V1", " buyers-v1", "", null }) {
            assertEquals("Unsupported buyer dataset version",
                    assertThrows(IllegalArgumentException.class, () -> new BuyerDataset(version)).getMessage());
        }
    }
}
