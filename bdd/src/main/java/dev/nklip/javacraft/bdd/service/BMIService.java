package dev.nklip.javacraft.bdd.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import dev.nklip.javacraft.bdd.model.BmiCategory;
import org.springframework.stereotype.Service;

/**
 * Body mass index (BMI) is a measurement of a person's weight in relation to their height.
 * <p>
 * It offers an inexpensive and simple method of categorising people according to their BMI value
 * so that we can screen people’s weight category and indicate their potential risk for health conditions.
 */
@Service
public class BMIService {
    private static final int BMI_CATEGORY_SCALE = 2;

    /**
     * BMI = Weight (kg) / Height (m)²
     * <p>
     * BMI = [Weight (lbs) / Height (inches)²] x 703
     * <p>
     * The imperial BMI formula is your weight in pounds (lbs) divided by your height in inches,
     * squared and then you multiply this figure by a conversion factor of 703.
     */
    public BigDecimal calculate(BigDecimal weight, BigDecimal height, boolean isImperial) {
        if (weight == null || height == null) {
            throw new IllegalArgumentException("Weight and height must not be null");
        }
        if (weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Weight must be positive");
        }
        if (height.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Height must be positive");
        }
        if (isImperial) {
            BigDecimal result = weight.divide(height.multiply(height), 10, RoundingMode.HALF_EVEN);
            return result.multiply(BigDecimal.valueOf(703)).setScale(2, RoundingMode.HALF_EVEN);
        } else {
            return weight.divide(height.multiply(height), 2, RoundingMode.HALF_EVEN);
        }
    }

    /**
     * Severe thinness < 16.00
     * Moderate thinness 16.00 - 16.99
     * Mild thinness 17.00 - 18.49
     * Underweight < 18.50
     * Normal range 18.50 - 24.99
     * Pre-obese 25.00 - 29.99
     * Obese ≥ 30.00
     * Obese class I 30.00 - 34.99
     * Obese class II 35.00 - 39.99
     * Obese class III ≥40.00
     */
    public String bmiToCategory(BigDecimal bmi) {
        if (bmi == null) {
            throw new IllegalArgumentException("BMI must not be null");
        }
        if (bmi.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("BMI must be positive");
        }
        return BmiCategory.from(normalizeBmiForCategory(bmi)).message();
    }

    /**
     * Category boundaries are defined to two decimal places, so classification
     * uses the same normalization strategy as BMI calculation output.
     */
    private BigDecimal normalizeBmiForCategory(BigDecimal bmi) {
        return bmi.setScale(BMI_CATEGORY_SCALE, RoundingMode.HALF_EVEN);
    }
}
