package dev.nklip.javacraft.openflights.jpa.normalize;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CountryNameNormalizerTest {

    private final CountryNameNormalizer countryNameNormalizer = new CountryNameNormalizer();

    @Test
    void keepsCanonicalCountryNames() {
        Assertions.assertEquals("Papua New Guinea", countryNameNormalizer.normalize("Papua New Guinea"));
    }

    @Test
    void normalizesKnownAliasesAndSourceTypos() {
        Assertions.assertEquals("Cote d'Ivoire", countryNameNormalizer.normalize("Ivory Coast"));
        Assertions.assertEquals("Brunei Darussalam", countryNameNormalizer.normalize("Brunei"));
        Assertions.assertEquals("United Kingdom", countryNameNormalizer.normalize("UNited Kingdom"));
        Assertions.assertEquals("Russia", countryNameNormalizer.normalize("Russia]]"));
        Assertions.assertEquals("Palestine", countryNameNormalizer.normalize("West Bank"));
    }

    @Test
    void removesKnownNonCountryValues() {
        Assertions.assertNull(countryNameNormalizer.normalize("AEROPERLAS"));
        Assertions.assertNull(countryNameNormalizer.normalize("WATCHDOG"));
        Assertions.assertNull(countryNameNormalizer.normalize("Canadian Territories"));
    }

    @Test
    void returnsNullForBlankValues() {
        Assertions.assertNull(countryNameNormalizer.normalize(null));
        Assertions.assertNull(countryNameNormalizer.normalize(" "));
    }
}
