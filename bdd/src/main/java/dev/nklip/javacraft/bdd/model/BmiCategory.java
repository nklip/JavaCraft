package dev.nklip.javacraft.bdd.model;

import java.math.BigDecimal;

/**
 * Keeps BMI thresholds and user-facing labels together so category lookup
 * stays declarative and easy to extend.
 */
public enum BmiCategory {
    SEVERE_THINNESS(null, "15.99", "Severe thinness as your BMI is less than 16.00"),
    MODERATE_THINNESS(SEVERE_THINNESS, "16.99", "Moderate thinness as your BMI is between 16.00 and 16.99"),
    MILD_THINNESS(MODERATE_THINNESS, "18.49", "Mild thinness as your BMI is between 17.00 and 18.49"),
    NORMAL_RANGE(MILD_THINNESS, "24.99", "Normal range as your BMI is between 18.50 and 24.99"),
    PRE_OBESE(NORMAL_RANGE, "29.99", "Pre-obese as your BMI is between 25.00 and 29.99"),
    OBESE_CLASS_I(PRE_OBESE, "34.99", "Obese class I as your BMI is between 30.00 and 34.99"),
    OBESE_CLASS_II(OBESE_CLASS_I, "39.99", "Obese class II as your BMI is between 35.00 and 39.99"),
    OBESE_CLASS_III(OBESE_CLASS_II, null, "Obese class III as your BMI is 40.00 or more");

    private final BmiCategory lowerBoundary;
    private final BigDecimal upperBoundary;
    private final String message;

    BmiCategory(BmiCategory lowerBoundary, String upperBoundary, String message) {
        this.lowerBoundary = lowerBoundary;
        this.upperBoundary = upperBoundary != null ? new BigDecimal(upperBoundary) : null;
        this.message = message;
    }

    public String message() {
        return message;
    }

    public static BmiCategory from(BigDecimal bmi) {
        for (BmiCategory category : values()) {
            boolean aboveLowerBoundary = category.lowerBoundary == null
                    || bmi.compareTo(category.lowerBoundary.upperBoundary) > 0;
            boolean belowUpperBoundary = category.upperBoundary == null
                    || bmi.compareTo(category.upperBoundary) <= 0;

            if (aboveLowerBoundary && belowUpperBoundary) {
                return category;
            }
        }
        throw new IllegalStateException("No BMI category matched value " + bmi);
    }
}
