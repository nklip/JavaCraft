package dev.nklip.javacraft.mathparser.parser;

import java.util.Map;

/**
 * @author Lipatov Nikita
 * @see Exception
 **/
public class ParserException extends Exception {

    public static final int EXPRESSION_MAX_LENGTH = 1024;
    public static final int IDENTIFIER_MAX_LENGTH = 32;
    // 12 is the last int number that is less than: factorial(12) < Integer.MAX_VALUE
    public static final int FACTORIAL_MAX_NUMBER = 12;

    private static final Map<Error, String> errorMessages = Map.ofEntries(
            Map.entry(Error.SYNTAX, "Syntax error"),
            Map.entry(Error.UNBAL_PARENTS, "Unbalanced brackets"),
            Map.entry(Error.NO_EXPRESSION, "Expression wasn't found"),
            Map.entry(Error.DIVISION_BY_ZERO, "Division by zero"),
            Map.entry(Error.UNKNOWN_EXPRESSION, "Unknown expression"),
            Map.entry(Error.UNKNOWN_FUNCTION, "Unknown function"),
            Map.entry(Error.UNKNOWN_VARIABLE, "Unknown variable"),
            Map.entry(Error.TOO_BIG, "Expression is too big (max '%s' characters)".formatted(EXPRESSION_MAX_LENGTH)),
            Map.entry(Error.IDENTIFIER_TOO_LONG, "Identifier is too long (max '%s' characters)".formatted(IDENTIFIER_MAX_LENGTH)),
            Map.entry(Error.NON_NEGATIVE_INTEGERS, "Factorial requires non-negative integers."),
            Map.entry(Error.NUMERIC_OVERFLOW, "Numeric overflow.")
    );

    private final Error typeError;

    public ParserException(Error typeError) {
        this.typeError = typeError;
    }

    @Override
    public String toString() {
        return errorMessages.get(typeError);
    }

    public enum Error {
        SYNTAX,
        UNBAL_PARENTS,
        NO_EXPRESSION,
        DIVISION_BY_ZERO,
        UNKNOWN_EXPRESSION,
        UNKNOWN_FUNCTION,
        UNKNOWN_VARIABLE,
        TOO_BIG,
        IDENTIFIER_TOO_LONG,
        NON_NEGATIVE_INTEGERS,
        NUMERIC_OVERFLOW
    }

}
