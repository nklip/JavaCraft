package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class CompaniesDownloaded {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String WIKIDATA_SPARQL_ENDPOINT = "https://query.wikidata.org/sparql";
    private static final int TARGET_COMPANIES_COUNT = 100;
    private static final int DETAIL_PROGRESS_LOG_EVERY = 10;
    private static final int DETAIL_REQUEST_PAUSE_MILLIS = 500;
    private static final int CURL_MAX_ATTEMPTS = 3;
    private static final int CURL_MAX_TIME_SECONDS = 30;
    private static final int CURL_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int CURL_INITIAL_BACKOFF_MILLIS = 1_000;
    private static final int CURL_MAX_BACKOFF_MILLIS = 30_000;
    private static final Pattern RETRYABLE_HTTP_STATUS_PATTERN = Pattern.compile("\\b(429|503|504)\\b");

    /**
     * Step 1: fetch ranked top companies by market capitalization in USD.
     * We keep this query intentionally lightweight to avoid endpoint timeouts.
     */
    private static final String TOP_COMPANIES_QUERY_TEMPLATE = """
            SELECT ?company ?companyLabel (MAX(?marketCapUsd) AS ?marketCap) WHERE {
              ?company wdt:P31 wd:Q891723 .
              ?company p:P2226 ?marketCapStatement .
              ?marketCapStatement psv:P2226 ?marketCapNode .
              ?marketCapNode wikibase:quantityAmount ?marketCapUsd .
              ?marketCapNode wikibase:quantityUnit wd:Q4917 .
              ?company rdfs:label ?companyLabel .
              FILTER(LANG(?companyLabel) = "en")
              FILTER(?marketCapUsd > 0)
            }
            GROUP BY ?company ?companyLabel
            ORDER BY DESC(?marketCap)
            LIMIT %d
            """;

    /**
     * Step 2: for the companies selected in step 1, fetch all requested fields in batches.
     */
    private static final String COMPANY_DETAILS_QUERY_TEMPLATE = """
            SELECT ?company
                   (SAMPLE(?countryLabelResolved) AS ?countryLabelSample)
                   (SAMPLE(?headquartersLabel) AS ?headquartersLabelSample)
                   (SAMPLE(?industryLabel) AS ?industryLabelSample)
                   (SAMPLE(?sectorLabelResolved) AS ?sectorLabelSample)
                   (SAMPLE(?ceoLabel) AS ?ceoLabelSample)
                   (SAMPLE(?employees) AS ?employeesSample)
                   (SAMPLE(?founded) AS ?foundedSample)
                   (SAMPLE(?website) AS ?websiteSample)
                   (SAMPLE(?description) AS ?descriptionSample)
            WHERE {
              VALUES ?company { %s }

              OPTIONAL {
                ?company wdt:P17 ?countryFromP17 .
                ?countryFromP17 rdfs:label ?countryLabelFromP17 .
                FILTER(LANG(?countryLabelFromP17) = "en")
              }
              OPTIONAL {
                ?company wdt:P495 ?countryFromP495 .
                ?countryFromP495 rdfs:label ?countryLabelFromP495 .
                FILTER(LANG(?countryLabelFromP495) = "en")
              }
              BIND(COALESCE(?countryLabelFromP17, ?countryLabelFromP495) AS ?countryLabelResolved)

              OPTIONAL {
                ?company wdt:P159 ?headquarters .
                ?headquarters rdfs:label ?headquartersLabel .
                FILTER(LANG(?headquartersLabel) = "en")
              }
              OPTIONAL {
                ?company wdt:P452 ?industry .
                ?industry rdfs:label ?industryLabel .
                FILTER(LANG(?industryLabel) = "en")
              }
              OPTIONAL {
                ?industry wdt:P279 ?sectorFromIndustry .
                ?sectorFromIndustry rdfs:label ?sectorLabelFromIndustry .
                FILTER(LANG(?sectorLabelFromIndustry) = "en")
              }
              BIND(COALESCE(?sectorLabelFromIndustry, ?industryLabel) AS ?sectorLabelResolved)

              OPTIONAL {
                ?company wdt:P169 ?ceo .
                ?ceo rdfs:label ?ceoLabel .
                FILTER(LANG(?ceoLabel) = "en")
              }

              OPTIONAL { ?company wdt:P1128 ?employees . }
              OPTIONAL { ?company wdt:P571 ?founded . }
              OPTIONAL { ?company wdt:P856 ?website . }
              OPTIONAL { ?company schema:description ?description FILTER(LANG(?description) = "en") }
            }
            GROUP BY ?company
            ORDER BY ?company
            """;

    private static final Path DEFAULT_OUTPUT_FILE = Path.of(
            "elastic", "elastic-testing",
            "src", "test", "resources", "data", "json", "companies.json"
    );

    private CompaniesDownloaded() {
    }

    public static void main(String[] args) throws IOException {
        Path outputFile = resolveOutputFile(args);
        exportTopCompanies(outputFile);
    }

    public static void exportTopCompanies(Path outputFile) throws IOException {
        exportTopCompanies(TARGET_COMPANIES_COUNT, outputFile);
    }

    public static void exportTopCompanies(int targetCompaniesCount, Path outputFile) throws IOException {
        if (targetCompaniesCount <= 0) {
            throw new IllegalArgumentException("targetCompaniesCount must be positive");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("outputFile must not be null");
        }

        List<CompanyRecord> records = fetchTopCompanies(targetCompaniesCount);
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
        Files.writeString(outputFile, json, StandardCharsets.UTF_8);
        log.info("Exported {} companies to '{}'", records.size(), outputFile.toAbsolutePath());
    }

    private static List<CompanyRecord> fetchTopCompanies(int targetCompaniesCount) throws IOException {
        List<TopCompany> topCompanies = fetchTopCompanyCandidates(targetCompaniesCount);
        Map<String, CompanyDetails> detailsByCompanyUri = fetchCompanyDetails(topCompanies);

        List<CompanyRecord> records = new ArrayList<>();
        for (int index = 0; index < topCompanies.size(); index++) {
            TopCompany topCompany = topCompanies.get(index);
            CompanyDetails details = detailsByCompanyUri.getOrDefault(topCompany.companyUri(), CompanyDetails.empty());

            CompanyRecord record = new CompanyRecord();
            record.setRank(index + 1);
            record.setName(topCompany.companyName());
            record.setCountry(details.country());
            record.setHeadquarters(details.headquarters());
            record.setIndustry(details.industry());
            record.setSector(resolveSector(details.sector(), details.industry()));
            record.setCeo(details.ceo());
            record.setEmployees(details.employees());
            record.setFounded(details.foundedYear());
            record.setWebsite(details.website());
            record.setDescription(details.description());

            records.add(record);
            log.info(
                    "Downloaded company {}/{}: '{}' (country='{}', ceo='{}')",
                    record.getRank(),
                    topCompanies.size(),
                    record.getName(),
                    record.getCountry(),
                    record.getCeo()
            );
        }

        return records;
    }

    private static List<TopCompany> fetchTopCompanyCandidates(int targetCompaniesCount) throws IOException {
        JsonNode bindings = executeSparql(TOP_COMPANIES_QUERY_TEMPLATE.formatted(targetCompaniesCount));
        List<TopCompany> companies = new ArrayList<>();
        for (JsonNode binding : bindings) {
            String companyUri = value(binding, "company");
            String companyName = value(binding, "companyLabel");
            if (companyUri.isBlank() || companyName.isBlank()) {
                continue;
            }
            companies.add(new TopCompany(companyUri, companyName));
        }

        if (companies.isEmpty()) {
            throw new IOException("No company candidates were returned by Wikidata");
        }
        return companies;
    }

    private static Map<String, CompanyDetails> fetchCompanyDetails(List<TopCompany> topCompanies) throws IOException {
        Map<String, CompanyDetails> detailsByCompanyUri = new HashMap<>();

        for (int index = 0; index < topCompanies.size(); index++) {
            TopCompany company = topCompanies.get(index);
            fetchCompanyDetailsForSingleCompany(company).ifPresent(details ->
                    detailsByCompanyUri.put(company.companyUri(), details)
            );

            int processed = index + 1;
            if (processed % DETAIL_PROGRESS_LOG_EVERY == 0 || processed == topCompanies.size()) {
                log.info(
                        "Downloaded details for {}/{} companies",
                        processed,
                        topCompanies.size()
                );
            }
            if (processed < topCompanies.size()) {
                pauseBetweenDetailRequests();
            }
        }

        return detailsByCompanyUri;
    }

    private static Optional<CompanyDetails> fetchCompanyDetailsForSingleCompany(TopCompany company) throws IOException {
        String valuesClause = "<%s>".formatted(company.companyUri());
        try {
            JsonNode bindings = executeSparql(COMPANY_DETAILS_QUERY_TEMPLATE.formatted(valuesClause));
            for (JsonNode binding : bindings) {
                if (company.companyUri().equals(value(binding, "company"))) {
                    return Optional.of(toCompanyDetails(binding));
                }
            }
            return Optional.empty();
        } catch (IOException ex) {
            log.warn(
                    "Skipping details for '{}' ({}) after retry failure: {}",
                    company.companyName(),
                    company.companyUri(),
                    sanitize(ex.getMessage())
            );
            return Optional.empty();
        }
    }

    private static CompanyDetails toCompanyDetails(JsonNode binding) {
        return new CompanyDetails(
                value(binding, "countryLabelSample"),
                value(binding, "headquartersLabelSample"),
                value(binding, "industryLabelSample"),
                value(binding, "sectorLabelSample"),
                value(binding, "ceoLabelSample"),
                parseLong(value(binding, "employeesSample")),
                parseYear(value(binding, "foundedSample")),
                normalizeWebsite(value(binding, "websiteSample")),
                value(binding, "descriptionSample")
        );
    }

    private static JsonNode executeSparql(String sparqlQuery) throws IOException {
        return executeSparqlWithCurl(sparqlQuery);
    }

    private static JsonNode executeSparqlWithCurl(String sparqlQuery) throws IOException {
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= CURL_MAX_ATTEMPTS; attempt++) {
            CurlResult result = executeCurlOnce(sparqlQuery);
            if (result.exitCode() == 0) {
                return parseBindings(result.output());
            }

            String output = sanitize(result.output());
            boolean retryable = isRetryableCurlFailure(result.exitCode(), output);
            lastFailure = new IOException(
                    "curl transport failed (exit=%d, attempt=%d/%d): %s"
                            .formatted(result.exitCode(), attempt, CURL_MAX_ATTEMPTS, output)
            );
            if (!retryable || attempt == CURL_MAX_ATTEMPTS) {
                throw lastFailure;
            }

            int delayMillis = Math.min(
                    CURL_INITIAL_BACKOFF_MILLIS * (1 << (attempt - 1)),
                    CURL_MAX_BACKOFF_MILLIS
            );
            log.warn(
                    "Transient curl failure (exit={}, attempt {}/{}). Retrying in {} ms",
                    result.exitCode(),
                    attempt,
                    CURL_MAX_ATTEMPTS,
                    delayMillis
            );
            sleepMillis(delayMillis);
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IOException("curl transport failed with unknown error");
    }

    private static CurlResult executeCurlOnce(String sparqlQuery) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "curl",
                "-sS",
                "--fail-with-body",
                "--max-time", Integer.toString(CURL_MAX_TIME_SECONDS),
                "--connect-timeout", Integer.toString(CURL_CONNECT_TIMEOUT_SECONDS),
                "--get",
                WIKIDATA_SPARQL_ENDPOINT,
                "-H", "Accept: application/sparql-results+json, application/json",
                "--data-urlencode", "format=json",
                "--data-urlencode", "query=" + sparqlQuery
        );
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        byte[] output = process.getInputStream().readAllBytes();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("curl execution interrupted", ex);
        }
        return new CurlResult(exitCode, new String(output, StandardCharsets.UTF_8));
    }

    private static boolean isRetryableCurlFailure(int exitCode, String output) {
        String normalizedOutput = sanitize(output).toLowerCase();
        if (exitCode == 28 || exitCode == 7 || exitCode == 52 || exitCode == 56) {
            return true;
        }
        return exitCode == 22
                && (RETRYABLE_HTTP_STATUS_PATTERN.matcher(normalizedOutput).find()
                    || normalizedOutput.contains("too many requests")
                    || normalizedOutput.contains("operation timed out")
                    || normalizedOutput.contains("temporarily unavailable"));
    }

    private static void pauseBetweenDetailRequests() throws IOException {
        sleepMillis(DETAIL_REQUEST_PAUSE_MILLIS);
    }

    private static void sleepMillis(int millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting between retries", ex);
        }
    }

    private static JsonNode parseBindings(String responseBody) throws IOException {
        if (responseBody.isBlank()) {
            throw new IOException("Received empty response while downloading companies");
        }

        JsonNode bindings = OBJECT_MAPPER.readTree(responseBody).path("results").path("bindings");
        if (!bindings.isArray()) {
            throw new IOException("Unexpected SPARQL response format for companies");
        }
        return bindings;
    }

    private static String resolveSector(String sector, String industry) {
        String normalizedSector = sanitize(sector);
        if (!normalizedSector.isEmpty()) {
            return normalizedSector;
        }
        return sanitize(industry);
    }

    private static Long parseLong(String value) {
        String normalized = sanitize(value);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(normalized).longValue();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseYear(String value) {
        String normalized = sanitize(value);
        if (normalized.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(normalized.substring(0, 4));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String normalizeWebsite(String website) {
        String normalized = sanitize(website);
        if (normalized.endsWith("//")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String value(JsonNode binding, String field) {
        return sanitize(binding.path(field).path("value").asText(""));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static Path resolveOutputFile(String[] args) {
        return Optional.ofNullable(args)
                .filter(values -> values.length > 0)
                .filter(values -> values[0] != null)
                .filter(values -> !values[0].isBlank())
                .map(values -> Path.of(values[0].trim()))
                .orElse(DEFAULT_OUTPUT_FILE);
    }

    private record CurlResult(int exitCode, String output) {
    }

    private record TopCompany(String companyUri, String companyName) {
    }

    private record CompanyDetails(
            String country,
            String headquarters,
            String industry,
            String sector,
            String ceo,
            Long employees,
            Integer foundedYear,
            String website,
            String description
    ) {
        static CompanyDetails empty() {
            return new CompanyDetails("", "", "", "", "", null, null, "", "");
        }
    }

    @Data
    public static class CompanyRecord {
        private Integer rank;
        private String name;
        private String country;
        private String headquarters;
        private String industry;
        private String sector;
        private String ceo;
        private Long employees;
        private Integer founded;
        private String website;
        private String description;
    }
}
