package dev.nklip.javacraft.bdd.service;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BMIServiceTest {

    private final BMIService bmiService = new BMIService();

    @Test
    void testCalculateThrowsExceptionWhenWeightIsNull() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.calculate(null, BigDecimal.ONE, false)
        );
        Assertions.assertEquals("Weight and height must not be null", ex.getMessage());
    }

    @Test
    void testCalculateThrowsExceptionWhenHeightIsNull() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.calculate(BigDecimal.ONE, null, false)
        );
        Assertions.assertEquals("Weight and height must not be null", ex.getMessage());
    }

    @Test
    void testCalculateThrowsExceptionWhenBothAreNull() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.calculate(null, null, true)
        );
        Assertions.assertEquals("Weight and height must not be null", ex.getMessage());
    }

    @Test
    void testCalculateThrowsExceptionWhenWeightIsZero() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.calculate(BigDecimal.ZERO, BigDecimal.ONE, false)
        );
        Assertions.assertEquals("Weight must be positive", ex.getMessage());
    }

    @Test
    void testCalculateThrowsExceptionWhenWeightIsNegative() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.calculate(BigDecimal.valueOf(-70), BigDecimal.valueOf(1.75), false)
        );
        Assertions.assertEquals("Weight must be positive", ex.getMessage());
    }

    @Test
    void testCalculateThrowsExceptionWhenHeightIsZero() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.calculate(BigDecimal.valueOf(75), BigDecimal.ZERO, false)
        );
        Assertions.assertEquals("Height must be positive", ex.getMessage());
    }

    @Test
    void testCalculateThrowsExceptionWhenHeightIsNegative() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.calculate(BigDecimal.valueOf(75), BigDecimal.valueOf(-1.75), true)
        );
        Assertions.assertEquals("Height must be positive", ex.getMessage());
    }

    @Test
    void testCalculateMetricBmi() {
        BigDecimal result = bmiService.calculate(
                BigDecimal.valueOf(75), BigDecimal.valueOf(1.75), false);
        Assertions.assertEquals(new BigDecimal("24.49"), result);
    }

    @Test
    void testCalculateImperialBmi() {
        BigDecimal result = bmiService.calculate(
                BigDecimal.valueOf(150), BigDecimal.valueOf(65), true);
        Assertions.assertEquals(new BigDecimal("24.96"), result);
    }

    @Test
    void testBmiToCategoryThrowsExceptionWhenBmiIsNull() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.bmiToCategory(null)
        );
        Assertions.assertEquals("BMI must not be null", ex.getMessage());
    }

    @Test
    void testBmiToCategoryThrowsExceptionWhenBmiIsZero() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.bmiToCategory(BigDecimal.ZERO)
        );
        Assertions.assertEquals("BMI must be positive", ex.getMessage());
    }

    @Test
    void testBmiToCategoryThrowsExceptionWhenBmiIsNegative() {
        IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bmiService.bmiToCategory(BigDecimal.valueOf(-1))
        );
        Assertions.assertEquals("BMI must be positive", ex.getMessage());
    }

    @Test
    void testBmiToCategoryReturnsExpectedCategoryForBoundaryValues() {
        List<Object[]> testCases = List.of(
                new Object[] {new BigDecimal("0.01"), "Severe thinness as your BMI is less than 16.00"},
                new Object[] {new BigDecimal("15.99"), "Severe thinness as your BMI is less than 16.00"},
                new Object[] {new BigDecimal("16.00"), "Moderate thinness as your BMI is between 16.00 and 16.99"},
                new Object[] {new BigDecimal("16.99"), "Moderate thinness as your BMI is between 16.00 and 16.99"},
                new Object[] {new BigDecimal("17.00"), "Mild thinness as your BMI is between 17.00 and 18.49"},
                new Object[] {new BigDecimal("18.49"), "Mild thinness as your BMI is between 17.00 and 18.49"},
                new Object[] {new BigDecimal("18.50"), "Normal range as your BMI is between 18.50 and 24.99"},
                new Object[] {new BigDecimal("24.99"), "Normal range as your BMI is between 18.50 and 24.99"},
                new Object[] {new BigDecimal("25.00"), "Pre-obese as your BMI is between 25.00 and 29.99"},
                new Object[] {new BigDecimal("29.99"), "Pre-obese as your BMI is between 25.00 and 29.99"},
                new Object[] {new BigDecimal("30.00"), "Obese class I as your BMI is between 30.00 and 34.99"},
                new Object[] {new BigDecimal("34.99"), "Obese class I as your BMI is between 30.00 and 34.99"},
                new Object[] {new BigDecimal("35.00"), "Obese class II as your BMI is between 35.00 and 39.99"},
                new Object[] {new BigDecimal("39.99"), "Obese class II as your BMI is between 35.00 and 39.99"},
                new Object[] {new BigDecimal("40.00"), "Obese class III as your BMI is 40.00 or more"},
                new Object[] {new BigDecimal("41.00"), "Obese class III as your BMI is 40.00 or more"}
        );

        for (Object[] testCase : testCases) {
            Assertions.assertEquals(testCase[1], bmiService.bmiToCategory((BigDecimal) testCase[0]));
        }
    }

    @Test
    void testBmiToCategoryNormalizesValuesWithMoreThanTwoDecimals() {
        List<Object[]> testCases = List.of(
                new Object[] {new BigDecimal("15.994"), "Severe thinness as your BMI is less than 16.00"},
                new Object[] {new BigDecimal("15.995"), "Moderate thinness as your BMI is between 16.00 and 16.99"},
                new Object[] {new BigDecimal("16.994"), "Moderate thinness as your BMI is between 16.00 and 16.99"},
                new Object[] {new BigDecimal("16.995"), "Mild thinness as your BMI is between 17.00 and 18.49"},
                new Object[] {new BigDecimal("39.994"), "Obese class II as your BMI is between 35.00 and 39.99"},
                new Object[] {new BigDecimal("39.995"), "Obese class III as your BMI is 40.00 or more"}
        );

        for (Object[] testCase : testCases) {
            Assertions.assertEquals(testCase[1], bmiService.bmiToCategory((BigDecimal) testCase[0]));
        }
    }

}
