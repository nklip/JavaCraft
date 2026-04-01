package my.javacraft.soap2rest.rest.app.service;

import my.javacraft.soap2rest.rest.api.Metric;
import org.springframework.stereotype.Service;

@Service
public class MetricValidationService {

    public void validate(Metric extracted, Metric submitted) {
        if (extracted != null) {
            if (extracted.getReading().compareTo(submitted.getReading()) > 0) {
                throw new IllegalArgumentException("New metrics should be higher than the previous read.");
            }
            if (extracted.getDate().compareTo(submitted.getDate()) == 0) {
                throw new IllegalArgumentException("You already submitted your metrics for today.");
            } else if (extracted.getDate().compareTo(submitted.getDate()) > 0) {
                throw new IllegalArgumentException("You are trying to submit for a past date.");
            }
        }
    }
}
