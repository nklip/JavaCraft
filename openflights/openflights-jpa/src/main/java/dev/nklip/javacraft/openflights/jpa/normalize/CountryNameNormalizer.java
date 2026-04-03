package dev.nklip.javacraft.openflights.jpa.normalize;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Canonicalizes OpenFlights country values before they are written into the SQL model.
 *
 * <p>Why this class exists:
 *
 * <p>The OpenFlights source files are not perfectly normalized. The same country can appear under different names
 * depending on the file or record, and some rows contain values in the "country" column that are not countries at all.
 * Examples include:
 *
 * <p>- real aliases such as {@code "Ivory Coast"} vs {@code "Cote d'Ivoire"}
 * <p>- historical or alternate spellings such as {@code "Swaziland"} vs {@code "Eswatini"}
 * <p>- geopolitical naming differences such as {@code "Republic of Korea"} vs {@code "South Korea"}
 * <p>- noisy garbage values where the source column contains an airline/operator name or internal note instead of a
 * country
 *
 * <p>If we stored those values exactly as they appear in the source files, several bad things would happen:
 *
 * <p>1. the same logical country could be inserted multiple times under different names
 * <p>2. foreign-key relationships from airline/airport rows to the country table would become inconsistent
 * <p>3. route and reference repair logic would have to deal with many near-duplicate country names
 * <p>4. obviously invalid values such as operator names would leak into the persistence layer
 *
 * <p>This normalizer prevents that by producing one persistence-oriented canonical value for a given source country
 * string. It is intentionally part of the JPA/persistence side of the application rather than the raw data parser,
 * because this is not just source parsing. It is a decision about what value is acceptable and canonical for the SQL
 * model.
 *
 * <p>Normalization rules used here:
 *
 * <p>- trim leading/trailing whitespace
 * <p>- collapse repeated internal whitespace
 * <p>- strip trailing {@code ]} characters that appear in some noisy rows
 * <p>- compare using lowercase lookup keys
 * <p>- map known aliases to the canonical country name expected by persistence
 * <p>- return {@code null} for known non-country garbage values
 *
 * <p>The output of this class is therefore not "the raw OpenFlights value". It is "the country value we want to use
 * consistently in PostgreSQL". That is the main reason the class exists.
 */
@Component
public final class CountryNameNormalizer {

    private static final Map<String, String> COUNTRY_ALIASES = Map.ofEntries(
            Map.entry("brunei", "Brunei Darussalam"),
            Map.entry("burma", "Myanmar"),
            Map.entry("cape verde", "Cabo Verde"),
            Map.entry("congo (brazzaville)", "Congo Republic"),
            Map.entry("congo (kinshasa)", "DR Congo"),
            Map.entry("democratic people's republic of korea", "North Korea"),
            Map.entry("democratic republic of congo", "DR Congo"),
            Map.entry("democratic republic of the congo", "DR Congo"),
            Map.entry("east timor", "Timor-Leste"),
            Map.entry("faroe islands", "Faeroe Islands"),
            Map.entry("hong kong sar of china", "Hong Kong"),
            Map.entry("ivory coast", "Cote d'Ivoire"),
            Map.entry("kyrgyzstan", "Kyrgyz Republic"),
            Map.entry("lao peoples democratic republic", "Laos"),
            Map.entry("macau", "Macao"),
            Map.entry("micronesia", "Micronesia, Fed. Sts."),
            Map.entry("netherland", "Netherlands"),
            Map.entry("republic of korea", "South Korea"),
            Map.entry("republic of the congo", "Congo Republic"),
            Map.entry("russian federation", "Russia"),
            Map.entry("saint helena", "St. Helena"),
            Map.entry("saint kitts and nevis", "St. Kitts and Nevis"),
            Map.entry("saint lucia", "St. Lucia"),
            Map.entry("saint pierre and miquelon", "St. Pierre and Miquelon"),
            Map.entry("saint vincent and the grenadines", "St. Vincent and the Grenadines"),
            Map.entry("somali republic", "Somalia"),
            Map.entry("svalbard", "Svalbard and Jan Mayen Islands"),
            Map.entry("swaziland", "Eswatini"),
            Map.entry("syrian arab republic", "Syria"),
            Map.entry("united kingdom", "United Kingdom"),
            Map.entry("virgin islands", "United States Virgin Islands"),
            Map.entry("wallis and futuna", "Wallis and Futuna Islands"),
            Map.entry("west bank", "Palestine")
    );

    // These values come from noisy OpenFlights airline rows where the "country" column
    // contains operator names, brands, or internal notes rather than a real country.
    private static final Set<String> NON_COUNTRY_VALUES = Set.of(
            "acom",
            "active aero",
            "aerocenter",
            "aerocesar",
            "aeroperlas",
            "aeropuma",
            "aerosol",
            "aerosun",
            "aerovaradero",
            "aerowee",
            "air class",
            "air freighter",
            "air print",
            "air-maur",
            "airex",
            "airflight",
            "airman",
            "airnat",
            "airpac",
            "airport helicopter",
            "airwave",
            "alamo",
            "alaska",
            "alcon",
            "aldawlyh air",
            "all star",
            "alnacional",
            "antares",
            "appalachian",
            "aquiline",
            "arizair",
            "armstrong",
            "asa pesada",
            "astoria",
            "asur",
            "atco",
            "atlantic nicaragua",
            "atlantis canada",
            "audi air",
            "aurora air",
            "ausa",
            "avemex",
            "avianca",
            "avinor",
            "avioquintana",
            "azimut",
            "air s",
            "boonville stage line",
            "company as",
            "canadian territories",
            "dragon",
            "lap",
            "odinn",
            "rentaxel",
            "s",
            "s.a.",
            "scheff",
            "swissbird",
            "uniform oscar",
            "veles",
            "watchdog"
    );

    public String normalize(String rawCountryName) {
        String cleanedCountryName = clean(rawCountryName);
        if (cleanedCountryName == null) {
            return null;
        }

        String lookupKey = toLookupKey(cleanedCountryName);
        if (NON_COUNTRY_VALUES.contains(lookupKey)) {
            return null;
        }

        return COUNTRY_ALIASES.getOrDefault(lookupKey, cleanedCountryName);
    }

    private String clean(String rawCountryName) {
        if (rawCountryName == null) {
            return null;
        }

        String cleanedCountryName = rawCountryName.trim().replaceAll("\\s+", " ");
        while (cleanedCountryName.endsWith("]")) {
            cleanedCountryName = cleanedCountryName.substring(0, cleanedCountryName.length() - 1).trim();
        }

        return cleanedCountryName.isBlank() ? null : cleanedCountryName;
    }

    private String toLookupKey(String countryName) {
        return countryName.toLowerCase(Locale.ROOT);
    }
}
