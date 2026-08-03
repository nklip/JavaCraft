package dev.nklip.javacraft.mathparser.parser;

import java.util.Locale;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * User: Lipatov Nikita
 */
public class ParserTest {

    // =========================================================================
    // ADDITION
    // =========================================================================

    @Test
    public void testAddition_chained() {
        Parser parser = new Parser();
        Assertions.assertEquals("6.0", parser.calculate(" 1 + 2 + 3"));
    }

    @Test
    public void testAddition_withDecimals() {
        Parser parser = new Parser();
        Assertions.assertEquals("4.0", parser.calculate("1.5 + 2.5"));
    }

    @Test
    public void testAddition_withNegativeSummand() {
        // Unary minus on the leading operand followed by addition
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("-5 + 8"));
    }

    @Test
    public void testAddition_withZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("7.0", parser.calculate("7 + 0"));
    }

    // =========================================================================
    // SUBTRACTION
    // =========================================================================

    @Test
    public void testSubtraction_chained() {
        Parser parser = new Parser();
        Assertions.assertEquals("31.0", parser.calculate("40 - 6 - 3"));
    }

    @Test
    public void testSubtraction_negativeResult() {
        Parser parser = new Parser();
        Assertions.assertEquals("-2.0", parser.calculate("3 - 5"));
    }

    @Test
    public void testSubtraction_fromZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("-4.0", parser.calculate("0 - 4"));
    }

    // =========================================================================
    // MULTIPLICATION
    // =========================================================================

    @Test
    public void testMultiplication_chained() {
        Parser parser = new Parser();
        Assertions.assertEquals("16.0", parser.calculate("2 * 2 * 2 * 2"));
    }

    @Test
    public void testMultiplication_byZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("5 * 0"));
    }

    @Test
    public void testMultiplication_byOne() {
        Parser parser = new Parser();
        Assertions.assertEquals("7.0", parser.calculate("7 * 1"));
    }

    @Test
    public void testMultiplication_withDecimals() {
        Parser parser = new Parser();
        Assertions.assertEquals("10.0", parser.calculate("2.5 * 4"));
    }

    // =========================================================================
    // DIVISION
    // =========================================================================

    @Test
    public void testDivision_chained() {
        Parser parser = new Parser();
        Assertions.assertEquals("8.0", parser.calculate("64.0 / 2 / 4"));
    }

    @Test
    public void testDivision_byOne() {
        Parser parser = new Parser();
        Assertions.assertEquals("8.0", parser.calculate("8 / 1"));
    }

    @Test
    public void testDivision_decimalResult() {
        // Integer operands with a fractional quotient
        Parser parser = new Parser();
        Assertions.assertEquals("2.5", parser.calculate("5 / 2"));
    }

    @Test
    public void testDivision_negativeNumerator() {
        Parser parser = new Parser();
        Assertions.assertEquals("-5.0", parser.calculate("-10 / 2"));
    }

    // =========================================================================
    // MODULO
    // =========================================================================

    @Test
    public void testModulo_remainder() {
        Parser parser = new Parser();
        Assertions.assertEquals("2.0", parser.calculate("12 % 5"));
    }

    @Test
    public void testModulo_noRemainder() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("10 % 5"));
    }

    @Test
    public void testModulo_zeroNumerator() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("0 % 7"));
    }

    // =========================================================================
    // EXPONENTIATION
    // =========================================================================

    @Test
    public void testExponentiation_integerPower() {
        Parser parser = new Parser();
        Assertions.assertEquals("1024.0", parser.calculate("2^10"));
    }

    @Test
    public void testExponentiation_powerOfZero() {
        // Any base raised to the power of zero equals 1
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("5^0"));
    }

    @Test
    public void testExponentiation_powerOfOne() {
        // Any base raised to the power of one equals itself
        Parser parser = new Parser();
        Assertions.assertEquals("5.0", parser.calculate("5^1"));
    }

    @Test
    public void testExponentiation_fractionalExponent() {
        // 4^0.5 = √4 = 2: fractional exponents work as roots
        Parser parser = new Parser();
        Assertions.assertEquals("2.0", parser.calculate("4^0.5"));
    }

    // =========================================================================
    // COMBINATIONS
    // Operator precedence, brackets, implicit multiplication, constants.
    // =========================================================================

    @Test
    public void testCombination_operatorPrecedenceMultiplicationBeforeAddition() {
        // Multiplication binds tighter than addition: 2 + 3*4 = 2 + 12 = 14
        Parser parser = new Parser();
        Assertions.assertEquals("14.0", parser.calculate("2 + 3 * 4"));
    }

    @Test
    public void testCombination_bracketsOverridePrecedence() {
        Parser parser = new Parser();
        Assertions.assertEquals("28.0", parser.calculate("(2+5) * 4"));
    }

    @Test
    public void testCombination_nestedBrackets() {
        Parser parser = new Parser();
        Assertions.assertEquals("10.0", parser.calculate("((2+3)*2)"));
    }

    @Test
    public void testCombination_unaryMinusInBrackets() {
        Parser parser = new Parser();
        Assertions.assertEquals("12.0", parser.calculate("(-2+5) * 4"));
    }

    @Test
    public void testCombination_constantE() {
        Parser parser = new Parser();
        Assertions.assertEquals(Double.toString(Math.E * 4), parser.calculate("e * 4"));
    }

    @Test
    public void testCombination_constantPi() {
        Parser parser = new Parser();
        Assertions.assertEquals(Double.toString(Math.PI * 4), parser.calculate("pi * 4"));
    }

    @Test
    public void testCombination_implicitMultiplicationWithBrackets() {
        // "2(3+4)" is parsed as 2 * (3+4) = 14
        Parser parser = new Parser();
        Assertions.assertEquals("14.0", parser.calculate("2(3+4)"));
    }

    @Test
    public void testCombination_implicitMultiplicationWithConstant() {
        // "2pi" is parsed as 2 * π
        Parser parser = new Parser();
        Assertions.assertEquals(Double.toString(2 * Math.PI), parser.calculate("2pi"));
    }

    @Test
    public void testCombination_implicitMultiplicationBetweenBrackets() {
        // "(2+3)(4+5)" is parsed as 5 * 9 = 45
        Parser parser = new Parser();
        Assertions.assertEquals("45.0", parser.calculate("(2+3)(4+5)"));
    }

    @Test
    public void testCombination_implicitMultiplicationBeforeFunction() {
        // "3sin(90)" is parsed as 3 * sin(90) = 3 * 1 = 3
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("3sin(90)"));
    }

    // =========================================================================
    // VARIABLE OPERATIONS
    // Variables are stored per Parser instance.
    // =========================================================================

    @Test
    public void testVariable_assignAndUse() {
        Parser parser = new Parser();
        parser.calculate("var = 45");
        Assertions.assertEquals("180.0", parser.calculate("var * 4"));
    }

    @Test
    public void testVariable_reassign() {
        // Second assignment overwrites the first
        Parser parser = new Parser();
        parser.calculate("var = 45");
        parser.calculate("var = 10");
        Assertions.assertEquals("40.0", parser.calculate("var * 4"));
    }

    @Test
    public void testVariable_usedTwiceInExpression() {
        // The same variable can appear multiple times in one expression
        Parser parser = new Parser();
        parser.calculate("x = 5");
        Assertions.assertEquals("10.0", parser.calculate("x + x"));
    }

    @Test
    public void testVariable_usedAsArgumentInFunction() {
        // A variable can be passed directly as a function argument
        Parser parser = new Parser();
        parser.calculate("x = 4");
        Assertions.assertEquals("2.0", parser.calculate("sqrt(x)"));
    }

    @Test
    public void testVariable_multipleDistinct() {
        // Two independently assigned variables can be combined in one expression
        Parser parser = new Parser();
        parser.calculate("a = 3");
        parser.calculate("b = 4");
        Assertions.assertEquals("7.0", parser.calculate("a + b"));
    }

    // =========================================================================
    // ONE-PARAMETER FUNCTIONS
    // =========================================================================

    // ---- Trigonometric — degree mode (default) ----

    @Test
    public void testOneParamFunction_sinAtNinetyDegrees() {
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("sin(90)"));
    }

    @Test
    public void testOneParamFunction_sinAtZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("sin(0)"));
    }

    @Test
    public void testOneParamFunction_cosAtZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("cos(0)"));
    }

    @Test
    public void testOneParamFunction_cosAtNinetyDegrees() {
        // cos(90°) is not exactly 0 due to floating-point; round() normalises to 0
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("round(cos(90))"));
    }

    @Test
    public void testOneParamFunction_tanAtFortyFiveDegrees() {
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("round(tan(45))"));
    }

    @Test
    public void testOneParamFunction_tanAtZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("tan(0)"));
    }

    // ---- Trigonometric — gradian and radian modes ----

    @Test
    public void testOneParamFunction_sinAtNinetyGradians() {
        // 100 gradians = 90 degrees → sin = 1
        Parser parser = new Parser();
        parser.setTangentUnit(ParserType.GRADIAN);
        Assertions.assertEquals("1.0", parser.calculate("sin(100)"));
    }

    @Test
    public void testOneParamFunction_sinAtTwoHundredGradians() {
        // 200 gradians = 180 degrees → sin ≈ 0; round() normalises
        Parser parser = new Parser();
        parser.setTangentUnit(ParserType.GRADIAN);
        Assertions.assertEquals("0.0", parser.calculate("round(sin(200))"));
    }

    @Test
    public void testOneParamFunction_sinThirtyRadians() {
        // sin(30 radians) ≈ −0.988; round() gives −1.0
        Parser parser = new Parser();
        parser.setTangentUnit(ParserType.RADIAN);
        Assertions.assertEquals("-1.0", parser.calculate("round(sin(30))"));
    }

    // ---- Inverse trigonometric ----

    @Test
    public void testOneParamFunction_acosAtZeroInDefaultMode() {
        // acos(0) in degree mode = 90°; multiplied by 2 gives 180°
        Parser parser = new Parser();
        Assertions.assertEquals("180.0", parser.calculate("2*acos(0)"));
    }

    @Test
    public void testOneParamFunction_asinAtZeroInDefaultMode() {
        // asin(0) in degree mode = 0°; multiplied by 2 stays 0°
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("2*asin(0)"));
    }

    @Test
    public void testOneParamFunction_atanAtZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("atan(0)"));
    }

    @Test
    public void testOneParamFunction_inverseTrigInDegreeMode() {
        // asin(1)=90°, acos(0)=90°, atan(1)=45° when using degree mode
        Parser parser = new Parser(ParserType.DEGREE);
        double asinResult = Double.parseDouble(parser.calculate("asin(1)"));
        double acosResult = Double.parseDouble(parser.calculate("acos(0)"));
        double atanResult = Double.parseDouble(parser.calculate("atan(1)"));

        Assertions.assertEquals(90.0, asinResult, 1.0e-9);
        Assertions.assertEquals(90.0, acosResult, 1.0e-9);
        Assertions.assertEquals(45.0, atanResult, 1.0e-9);
    }

    @Test
    public void testOneParamFunction_inverseTrigInRadianMode() {
        // asin(1)=π/2, acos(0)=π/2, atan(1)=π/4 when using radian mode
        Parser parser = new Parser(ParserType.RADIAN);
        double asinResult = Double.parseDouble(parser.calculate("asin(1)"));
        double acosResult = Double.parseDouble(parser.calculate("acos(0)"));
        double atanResult = Double.parseDouble(parser.calculate("atan(1)"));

        Assertions.assertEquals(Math.PI / 2, asinResult, 1.0e-9);
        Assertions.assertEquals(Math.PI / 2, acosResult, 1.0e-9);
        Assertions.assertEquals(Math.PI / 4, atanResult, 1.0e-9);
    }

    // ---- Logarithm and exponential ----

    @Test
    public void testOneParamFunction_log10OfOneThousand() {
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("round(log10(1000))"));
    }

    @Test
    public void testOneParamFunction_lnOfECubed() {
        // ln(e³) = 3; round() removes any floating-point noise
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("round(ln(e^3))"));
    }

    @Test
    public void testOneParamFunction_lnOfOne() {
        // ln(1) = 0 exactly
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("ln(1)"));
    }

    @Test
    public void testOneParamFunction_lnOfE() {
        // ln(e) = 1; round() removes any floating-point noise
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("round(ln(e))"));
    }

    @Test
    public void testOneParamFunction_expOfOne() {
        // exp(1) = e; compared with tolerance to avoid string format differences
        Parser parser = new Parser();
        Assertions.assertEquals(Math.exp(1), Double.parseDouble(parser.calculate("exp(1)")), 1.0e-12);
    }

    // ---- Square and cube root ----

    @Test
    public void testOneParamFunction_sqrtPerfectSquare() {
        Parser parser = new Parser();
        Assertions.assertEquals("12.0", parser.calculate("sqrt(144)"));
    }

    @Test
    public void testOneParamFunction_sqrtOfZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("sqrt(0)"));
    }

    @Test
    public void testOneParamFunction_cbrtPerfectCube() {
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("cbrt(27)"));
    }

    @Test
    public void testOneParamFunction_cbrtNegativeArgument() {
        // Cube root is defined for negative numbers: ∛(−8) = −2
        Parser parser = new Parser();
        Assertions.assertEquals("-2.0", parser.calculate("cbrt(-8)"));
    }

    // ---- Rounding ----

    @Test
    public void testOneParamFunction_ceilOfPositiveDecimal() {
        Parser parser = new Parser();
        Assertions.assertEquals("2.0", parser.calculate("ceil(1.2)"));
    }

    @Test
    public void testOneParamFunction_ceilOfNegativeDecimal() {
        // ceil rounds toward positive infinity: ceil(−1.8) = −1
        Parser parser = new Parser();
        Assertions.assertEquals("-1.0", parser.calculate("ceil(-1.8)"));
    }

    @Test
    public void testOneParamFunction_floorOfPositiveDecimal() {
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("floor(1.8)"));
    }

    @Test
    public void testOneParamFunction_floorOfNegativeDecimal() {
        // floor rounds toward negative infinity: floor(−1.2) = −2
        Parser parser = new Parser();
        Assertions.assertEquals("-2.0", parser.calculate("floor(-1.2)"));
    }

    // ---- Absolute value ----

    @Test
    public void testOneParamFunction_absOfNegative() {
        Parser parser = new Parser();
        Assertions.assertEquals("100.0", parser.calculate("abs(-100)"));
    }

    @Test
    public void testOneParamFunction_absOfPositive() {
        // abs of an already-positive number returns the same value
        Parser parser = new Parser();
        Assertions.assertEquals("5.0", parser.calculate("abs(5)"));
    }

    @Test
    public void testOneParamFunction_absOfZero() {
        Parser parser = new Parser();
        Assertions.assertEquals("0.0", parser.calculate("abs(0)"));
    }

    // ---- Factorial ----

    @Test
    public void testOneParamFunction_factorialOfFive() {
        Parser parser = new Parser();
        Assertions.assertEquals("120.0", parser.calculate("factorial(5)"));
    }

    @Test
    public void testOneParamFunction_factorialOfZero() {
        // 0! = 1 by convention
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("factorial(0)"));
    }

    @Test
    public void testOneParamFunction_factorialOfOne() {
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("factorial(1)"));
    }

    // =========================================================================
    // TWO-PARAMETER FUNCTIONS
    // =========================================================================

    @Test
    public void testTwoParamFunction_powIntegerExponent() {
        Parser parser = new Parser();
        Assertions.assertEquals("1024.0", parser.calculate("pow(2,10)"));
    }

    @Test
    public void testTwoParamFunction_powBaseOne() {
        // 1 raised to any power is always 1
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("pow(1,100)"));
    }

    @Test
    public void testTwoParamFunction_powExponentZero() {
        // Any base raised to exponent 0 is 1
        Parser parser = new Parser();
        Assertions.assertEquals("1.0", parser.calculate("pow(5,0)"));
    }

    @Test
    public void testTwoParamFunction_logBase10() {
        // log₁₀(1000) = 3
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("round(log(10,1000))"));
    }

    @Test
    public void testTwoParamFunction_logBase2() {
        // log₂(8) = 3
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("round(log(2,8))"));
    }

    // =========================================================================
    // MULTI-PARAMETER FUNCTIONS
    // =========================================================================

    @Test
    public void testMultiParamFunction_maxOfThreePositive() {
        Parser parser = new Parser();
        Assertions.assertEquals("5.0", parser.calculate("max(2,3,5)"));
    }

    @Test
    public void testMultiParamFunction_maxOfThreeNegative() {
        // The largest (least negative) of three negative values
        Parser parser = new Parser();
        Assertions.assertEquals("-1.0", parser.calculate("max(-1,-5,-3)"));
    }

    @Test
    public void testMultiParamFunction_minOfThreePositive() {
        Parser parser = new Parser();
        Assertions.assertEquals("2.0", parser.calculate("min(2,3,5)"));
    }

    @Test
    public void testMultiParamFunction_minOfThreeNegative() {
        // The smallest (most negative) of three negative values
        Parser parser = new Parser();
        Assertions.assertEquals("-5.0", parser.calculate("min(-1,-5,-3)"));
    }

    @Test
    public void testMultiParamFunction_avgOfThree() {
        Parser parser = new Parser();
        Assertions.assertEquals("6.0", parser.calculate("avg(3,6,9)"));
    }

    @Test
    public void testMultiParamFunction_avgSymmetric() {
        // avg(1,2,3) = 2 — simple equidistant sequence
        Parser parser = new Parser();
        Assertions.assertEquals("2.0", parser.calculate("avg(1,2,3)"));
    }

    @Test
    public void testMultiParamFunction_sumOfThree() {
        Parser parser = new Parser();
        Assertions.assertEquals("180.0", parser.calculate("sum(30,60,90)"));
    }

    @Test
    public void testMultiParamFunction_sumOfTwo() {
        Parser parser = new Parser();
        Assertions.assertEquals("10.0", parser.calculate("sum(3,7)"));
    }

    // =========================================================================
    // WHITESPACE NORMALIZATION
    // Tab, newline, carriage return, and non-breaking space are all stripped.
    // =========================================================================

    @Test
    public void testWhitespaceNormalization_tabNewlineCarriageReturn() {
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("\t1 +\n2\r"));
    }

    @Test
    public void testWhitespaceNormalization_nonBreakingSpace() {
        // U+00A0 non-breaking spaces are treated the same as regular spaces
        Parser parser = new Parser();
        Assertions.assertEquals("3.0", parser.calculate("\u00A01\u00A0+\u00A02\u00A0"));
    }

    // =========================================================================
    // PARSER TYPE
    // =========================================================================

    @Test
    public void testParserType_enumValues() {
        Assertions.assertArrayEquals(
                new ParserType[]{ParserType.DEGREE, ParserType.GRADIAN, ParserType.RADIAN},
                ParserType.values()
        );
    }

    // =========================================================================
    // LOCALE SAFETY
    // Function names are lowercased with Locale.ROOT to avoid language-specific
    // case folding (e.g., Turkish 'I' → 'ı' instead of 'i').
    // =========================================================================

    @Test
    public void testLocaleSafety_uppercaseFunctionNameWithTurkishLocale() {
        // In Turkish locale, toLowerCase("SIN") without ROOT gives "sın" (dotless i)
        Locale originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            Parser parser = new Parser();
            Assertions.assertEquals("1.0", parser.calculate("SIN(90)"));
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

}
