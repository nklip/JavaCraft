package dev.nklip.javacraft.openflights.kafka.producer.model;

public record OpenFlightsImportResult(
        String dataset,
        int submittedRecords
) {
}
