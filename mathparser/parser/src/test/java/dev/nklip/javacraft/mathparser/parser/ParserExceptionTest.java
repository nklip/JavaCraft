package dev.nklip.javacraft.mathparser.parser;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * User: Lipatov Nikita
 */
public class ParserExceptionTest {

    // =========================================================================
    // SYNTAX
    // Raised when: trailing unconsumed token after a full parse, NumberFormatException
    // from a malformed numeric literal, leading non-unary operator, two-parameter
    // function called with wrong argument count.
    // =========================================================================

    @Test
    public void testSyntax_doubleDecimalPoint() {
        // "1..2" causes NumberFormatException inside Double.parseDouble, mapped to SYNTAX
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.SYNTAX).toString(),
                parser.calculate("1..2 + 3")
        );
    }

    @Test
    public void testSyntax_extraClosingBracket() {
        // A trailing ')' is left unconsumed after the full expression is parsed
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.SYNTAX).toString(),
                parser.calculate("((2+5) * 3))")
        );
    }

    @Test
    public void testSyntax_trailingPlusOperator() {
        // "1 +" has no right-hand operand: atom() receives an empty token
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.SYNTAX).toString(),
                parser.calculate("1 +")
        );
    }

    @Test
    public void testSyntax_leadingMultiplyOperator() {
        // '*' is not a unary operator; atom() receives a DELIMITER token
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.SYNTAX).toString(),
                parser.calculate("* 2")
        );
    }

    @Test
    public void testSyntax_tooFewArgsInTwoParamFunction() {
        // pow() requires exactly two comma-separated arguments; one comma is expected
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.SYNTAX).toString(),
                parser.calculate("pow(1)")
        );
    }

    @Test
    public void testSyntax_tooManyArgsInTwoParamFunction() {
        // A third argument after the comma in a two-parameter function is a syntax error
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.SYNTAX).toString(),
                parser.calculate("pow(1,2,3)")
        );
    }

    // =========================================================================
    // UNBAL_PARENTS
    // Raised when: an open '(' has no matching ')', or a function call is
    // missing its closing bracket.
    // =========================================================================

    @Test
    public void testUnbalancedParentheses_deepUnclosedExpression() {
        // Two open brackets but only one matching closing bracket
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNBAL_PARENTS).toString(),
                parser.calculate("((2+5) * 3")
        );
    }

    @Test
    public void testUnbalancedParentheses_simpleUnclosed() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNBAL_PARENTS).toString(),
                parser.calculate("(2+3")
        );
    }

    @Test
    public void testUnbalancedParentheses_unclosedFunctionCall() {
        // One-parameter function call is missing the closing bracket
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNBAL_PARENTS).toString(),
                parser.calculate("sin(90")
        );
    }

    @Test
    public void testUnbalancedParentheses_unclosedMultiParamFunction() {
        // Multi-parameter function call is missing the closing bracket
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNBAL_PARENTS).toString(),
                parser.calculate("max(1,2,3")
        );
    }

    // =========================================================================
    // NO_EXPRESSION
    // Raised when: input is null, or the input collapses to an empty string
    // after whitespace normalisation.
    // =========================================================================

    @Test
    public void testNoExpression_emptyString() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NO_EXPRESSION).toString(),
                parser.calculate("")
        );
    }

    @Test
    public void testNoExpression_nullInput() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NO_EXPRESSION).toString(),
                parser.calculate(null)
        );
    }

    @Test
    public void testNoExpression_mixedWhitespace() {
        // Tab, newline, non-breaking space and carriage return are all stripped by the normaliser
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NO_EXPRESSION).toString(),
                parser.calculate("\t\n\u00A0\r")
        );
    }

    @Test
    public void testNoExpression_regularSpaces() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NO_EXPRESSION).toString(),
                parser.calculate("   ")
        );
    }

    @Test
    public void testNoExpression_singleTab() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NO_EXPRESSION).toString(),
                parser.calculate("\t")
        );
    }

    // =========================================================================
    // DIVISION_BY_ZERO
    // Raised when: the right operand of '/' or '%' evaluates to 0.0.
    // =========================================================================

    @Test
    public void testDivisionByZero_divideByLiteralZero() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.DIVISION_BY_ZERO).toString(),
                parser.calculate("10 / 0")
        );
    }

    @Test
    public void testDivisionByZero_moduloByLiteralZero() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.DIVISION_BY_ZERO).toString(),
                parser.calculate("10 % 0")
        );
    }

    @Test
    public void testDivisionByZero_zeroDividedByZero() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.DIVISION_BY_ZERO).toString(),
                parser.calculate("0 / 0")
        );
    }

    @Test
    public void testDivisionByZero_divisorIsSubExpression() {
        // The denominator is a sub-expression that evaluates to zero at runtime
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.DIVISION_BY_ZERO).toString(),
                parser.calculate("1 / (3 - 3)")
        );
    }

    @Test
    public void testDivisionByZero_decimalDividendModuloByZero() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.DIVISION_BY_ZERO).toString(),
                parser.calculate("5.5 % 0")
        );
    }

    // =========================================================================
    // UNKNOWN_EXPRESSION
    // Raised when: a character that is not a delimiter, letter, or digit is
    // encountered while tokenising.
    // =========================================================================

    @Test
    public void testUnknownExpression_specialCharAtStart() {
        // '@' is not a valid token-start character
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_EXPRESSION).toString(),
                parser.calculate("@1 + 2")
        );
    }

    @Test
    public void testUnknownExpression_specialCharAfterDigit() {
        // '@' inside a numeric token is not a valid digit or '.'
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_EXPRESSION).toString(),
                parser.calculate("1@2")
        );
    }

    @Test
    public void testUnknownExpression_hashAtStart() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_EXPRESSION).toString(),
                parser.calculate("#5")
        );
    }

    @Test
    public void testUnknownExpression_specialCharInsideIdentifier() {
        // '#' inside an alphabetic token is neither alphanumeric nor a delimiter
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_EXPRESSION).toString(),
                parser.calculate("a#b + 1")
        );
    }

    @Test
    public void testUnknownExpression_leadingDecimalDot() {
        // '.' without a leading digit is not a valid numeric literal start
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_EXPRESSION).toString(),
                parser.calculate(".5 + 1")
        );
    }

    // =========================================================================
    // UNKNOWN_FUNCTION
    // Raised when: a word followed by '(' does not match any built-in function.
    // =========================================================================

    @Test
    public void testUnknownFunction_singleArgVariant() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_FUNCTION).toString(),
                parser.calculate("rtg(2 * 5)")
        );
    }

    @Test
    public void testUnknownFunction_multiArgVariant() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_FUNCTION).toString(),
                parser.calculate("customFn(1,2)")
        );
    }

    @Test
    public void testUnknownFunction_commonAlternativeNotation() {
        // 'tg' is the Russian/European notation for tangent; only 'tan' is recognised
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_FUNCTION).toString(),
                parser.calculate("tg(45)")
        );
    }

    @Test
    public void testUnknownFunction_typoOfKnownFunction() {
        // 'sinn' is a single-character typo of 'sin'
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_FUNCTION).toString(),
                parser.calculate("sinn(90)")
        );
    }

    // =========================================================================
    // UNKNOWN_VARIABLE
    // Raised when: a word without '(' is used in a position that expects a value
    // but has not been assigned in this parser instance.
    // =========================================================================

    @Test
    public void testUnknownVariable_undeclaredInExpression() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_VARIABLE).toString(),
                parser.calculate("x + 1")
        );
    }

    @Test
    public void testUnknownVariable_simpleUndeclared() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_VARIABLE).toString(),
                parser.calculate("y * 2")
        );
    }

    @Test
    public void testUnknownVariable_variableDefinedInDifferentInstance() {
        // Variables are stored per-instance; a second Parser does not inherit them
        Parser first = new Parser();
        first.calculate("x = 10");

        Parser second = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.UNKNOWN_VARIABLE).toString(),
                second.calculate("x + 1")
        );
    }

    // =========================================================================
    // TOO_BIG
    // Raised when: the expression length exceeds EXPRESSION_MAX_LENGTH (1024)
    // after whitespace normalisation.
    // =========================================================================

    @Test
    public void testTooBig_expressionExceedsMaxLength() {
        Parser parser = new Parser();
        String longExpression = "a".repeat(ParserException.EXPRESSION_MAX_LENGTH + 1);
        Assertions.assertEquals(
                new ParserException(ParserException.Error.TOO_BIG).toString(),
                parser.calculate(longExpression)
        );
    }

    // =========================================================================
    // IDENTIFIER_TOO_LONG
    // Raised when: a variable or function name exceeds IDENTIFIER_MAX_LENGTH (32).
    // =========================================================================

    @Test
    public void testIdentifierTooLong_variableNameExceedsMaxLength() {
        // Variable name one character beyond IDENTIFIER_MAX_LENGTH (32)
        Parser parser = new Parser();
        String identifier = "a".repeat(ParserException.IDENTIFIER_MAX_LENGTH + 1);
        Assertions.assertEquals(
                new ParserException(ParserException.Error.IDENTIFIER_TOO_LONG).toString(),
                parser.calculate(identifier + " + 1")
        );
    }

    @Test
    public void testIdentifierTooLong_functionNameExceedsMaxLength() {
        // Function name one character beyond IDENTIFIER_MAX_LENGTH (32)
        Parser parser = new Parser();
        String functionName = "b".repeat(ParserException.IDENTIFIER_MAX_LENGTH + 1);
        Assertions.assertEquals(
                new ParserException(ParserException.Error.IDENTIFIER_TOO_LONG).toString(),
                parser.calculate(functionName + "(1)")
        );
    }

    @Test
    public void testIdentifierTooLong_wellBeyondMaxLength() {
        // Name that is double the max length to confirm the check is not boundary-specific
        Parser parser = new Parser();
        String longName = "c".repeat(ParserException.IDENTIFIER_MAX_LENGTH * 2);
        Assertions.assertEquals(
                new ParserException(ParserException.Error.IDENTIFIER_TOO_LONG).toString(),
                parser.calculate(longName + " + 1")
        );
    }

    // =========================================================================
    // NON_NEGATIVE_INTEGERS
    // Raised by factorial() when the argument is negative or non-integer.
    // =========================================================================

    @Test
    public void testNonNegativeIntegers_negativeArgument() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NON_NEGATIVE_INTEGERS).toString(),
                parser.calculate("factorial(-1)")
        );
    }

    @Test
    public void testNonNegativeIntegers_nonIntegerFromExpression() {
        // ln(8) ≈ 2.08, which passes the negativity check but fails the integer check
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NON_NEGATIVE_INTEGERS).toString(),
                parser.calculate("factorial(ln(8))")
        );
    }

    @Test
    public void testNonNegativeIntegers_decimalArgument() {
        // 0.5 is positive but not an integer
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NON_NEGATIVE_INTEGERS).toString(),
                parser.calculate("factorial(0.5)")
        );
    }

    @Test
    public void testNonNegativeIntegers_smallNegativeDecimal() {
        // -0.1 is both negative and non-integer
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NON_NEGATIVE_INTEGERS).toString(),
                parser.calculate("factorial(-0.1)")
        );
    }

    // =========================================================================
    // NUMERIC_OVERFLOW
    // Raised by factorial() when the argument exceeds FACTORIAL_MAX_NUMBER (12).
    // =========================================================================

    @Test
    public void testNumericOverflow_firstOverflowValue() {
        // factorial(13) is the smallest argument that causes overflow
        Parser parser = new Parser();
        int firstOverflow = ParserException.FACTORIAL_MAX_NUMBER + 1;
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NUMERIC_OVERFLOW).toString(),
                parser.calculate("factorial(" + firstOverflow + ")")
        );
    }

    @Test
    public void testNumericOverflow_largeArgument() {
        Parser parser = new Parser();
        Assertions.assertEquals(
                new ParserException(ParserException.Error.NUMERIC_OVERFLOW).toString(),
                parser.calculate("factorial(100)")
        );
    }

    // =========================================================================
    // NullPointerException (Java exception, not a ParserException.Error)
    // Raised when null is passed to methods that require a non-null ParserType.
    // =========================================================================

    @Test
    public void testNullPointerException_setTangentUnitWithNull() {
        Parser parser = new Parser();
        Assertions.assertThrows(NullPointerException.class, () -> parser.setTangentUnit(null));
    }

    @Test
    public void testNullPointerException_constructorWithNull() {
        Assertions.assertThrows(NullPointerException.class, () -> new Parser(null));
    }
}
