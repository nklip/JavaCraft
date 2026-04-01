package dev.nklip.javacraft.mathparser.parser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Top-down parser.
 *
 * @author Lipatov Nikita
 * @version 1.0.0
 **/
public class Parser {
    private static final Set<String> ONE_PARAMETER_FUNCTIONS = Set.of(
            "abs", "acos", "asin", "atan", "cbrt", "ceil", "cos", "exp", "factorial", "floor", "ln", "log10", "round", "sin", "sqrt", "tan"
    );
    private static final Set<String> TWO_PARAMETER_FUNCTIONS = Set.of("pow", "log");
    private static final Set<String> MULTI_PARAMETER_FUNCTIONS = Set.of("min", "max", "sum", "avg");

    private volatile ParserType angleUnit; // unit of angle
    private String inputString;            // full string
    private int currentIndex;              // pointer in string
    private Types tokenType;               // type of current token
    private String token;                  // current token
    // Storage of variables
    private final Map<String, Double> storVars = new HashMap<>();

    {
        inputString = "";
        currentIndex = 0;
        tokenType = Types.NONE;
        token = "";
    }

    public Parser() {
        angleUnit = ParserType.DEGREE;
    }

    /**
     * @param unit unit of angle
     **/
    public Parser(ParserType unit) {
        angleUnit = Objects.requireNonNull(unit, "ParserType unit cannot be null");
    }

    public synchronized void setTangentUnit(ParserType unit) {
        angleUnit = Objects.requireNonNull(unit, "ParserType unit cannot be null");
    }

    /**
     * Method should transform input string into sequences of tokens, which should be calculated and return in output string.
     *
     * @param expression expression for parsing
     * @return String result of top-down parser or error message
     **/
    public synchronized String calculate(String expression) {
        try {
            if (expression == null) {
                throw new ParserException(ParserException.Error.NO_EXPRESSION);
            }
            expression = normalizeExpression(expression);
            if (expression.length() > ParserException.EXPRESSION_MAX_LENGTH) {
                throw new ParserException(ParserException.Error.TOO_BIG);
            }
            // Locale.ROOT should prevent misparse in some locales (e.g., Turkish)
            inputString = expression.toLowerCase(Locale.ROOT);
            currentIndex = 0;
            getToken();
            if (token.isEmpty()) {
                throw new ParserException(ParserException.Error.NO_EXPRESSION);
            }
            Number temp = new Number();
            firstStepParsing(temp);
            if (!token.isEmpty()) {
                throw new ParserException(ParserException.Error.SYNTAX);
            }
            return Double.toString(temp.get());
        } catch (NumberFormatException exception) {
            return new ParserException(ParserException.Error.SYNTAX).toString();
        } catch (ParserException exception) {
            return exception.toString();
        }
    }

    /**
     * Removes all whitespace-like characters so parser tokenization stays consistent for tabs/new lines/non-breaking spaces.
     */
    private String normalizeExpression(String expression) {
        StringBuilder normalized = new StringBuilder(expression.length());
        expression.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    /**
     * Method searches tokens of variable initialization
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void firstStepParsing(Number result) throws ParserException {
        String token;
        Types tempType;
        if (tokenType == Types.VARIABLE) {
            token = this.token;
            tempType = Types.VARIABLE;
            boolean hasTemporaryDefaultValue = false;
            if (!storVars.containsKey(token)) {
                storVars.put(token, 0.0);
                hasTemporaryDefaultValue = true;
            }
            getToken();
            if (!this.token.equals("=")) {
                putBack();
                if (hasTemporaryDefaultValue) {
                    storVars.remove(token);
                }
                this.token = token;
                tokenType = tempType;
            } else {
                getToken();
                secondStepParsing(result);
                storVars.put(token, result.get());
                return;
            }
        }
        secondStepParsing(result);
    }

    /**
     * Method returns pointer to the start position
     **/
    private void putBack() {
        for (int i = 0; i < token.length(); i++) {
            currentIndex--;
        }
    }

    /**
     * Method searches tokens of plus or minus.
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void secondStepParsing(Number result) throws ParserException {
        thirdStepParsing(result);
        String token;
        while ((token = this.token).equals("+") || token.equals("-")) {
            getToken();
            Number temp = new Number();
            thirdStepParsing(temp);
            if (token.equals("-")) {
                result.set(result.get() - temp.get());
            } else { // token.equals("+") - this condition is always true
                result.set(result.get() + temp.get());
            }
        }
    }

    /**
     * Method searches tokens of multiplication or divide.
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void thirdStepParsing(Number result) throws ParserException {
        fourthStepParsing(result);
        String token;
        while ((token = this.token).equals("*")
                || token.equals("/")
                || token.equals("%")
                || isImplicitMultiplicationToken()) {
            boolean implicitMultiplication = isImplicitMultiplicationToken();
            if (!implicitMultiplication) {
                getToken();
            }
            Number temp = new Number();
            fourthStepParsing(temp);
            switch (token) {
                case "/" -> {
                    if (temp.get() == 0.0) {
                        throw new ParserException(ParserException.Error.DIVISION_BY_ZERO);
                    }
                    result.set(result.get() / temp.get());
                }
                case "%" -> {
                    if (temp.get() == 0.0) {
                        throw new ParserException(ParserException.Error.DIVISION_BY_ZERO);
                    }
                    result.set(result.get() % temp.get());
                }
                case "*" -> result.set(result.get() * temp.get());
                default -> result.set(result.get() * temp.get());
            }
        }
    }

    /**
     * Method searches tokens of involution (math).
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void fourthStepParsing(Number result) throws ParserException {
        fifthStepParsing(result);
        if (token.equals("^")) {
            getToken();
            Number temp = new Number(0.0);
            fourthStepParsing(temp);
            result.set(Math.pow(result.get(), temp.get()));
        }
    }

    /**
     * Method searches tokens of unary symbols.
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void fifthStepParsing(Number result) throws ParserException {
        String str = "";
        if ((tokenType == Types.DELIMITER) && (token.equals("+") || token.equals("-"))) {
            str = token;
            getToken();
        }
        sixthStepParsing(result);
        if (str.equals("-")) {
            result.invertValue();
        }
    }

    /**
     * Method searches tokens of brackets.
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void sixthStepParsing(Number result) throws ParserException {
        if (token.equals("(")) {
            getToken();
            firstStepParsing(result);
            if (!token.equals(")")) {
                throw new ParserException(ParserException.Error.UNBAL_PARENTS);
            }
            getToken();
        } else {
            seventhStepParsing(result);
        }
    }

    /**
     * Method searches tokens of constants.
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void seventhStepParsing(Number result) throws ParserException {
        if (token.equals("e")) {
            result.set(Math.E);
            getToken();
        } else if (token.equals("pi")) {
            result.set(Math.PI);
            getToken();
        } else {
            atom(result);
        }
    }

    /**
     * Method returns value.
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void atom(Number result) throws ParserException {
        switch (tokenType) {
            case NUMBER:
                result.set(Double.parseDouble(token));
                getToken();
                return;
            case FUNCTION:
                functions(result);
                return;
            case VARIABLE:
                result.set(findVar(token));
                getToken();
                return;
            default:
                result.set(0.0);
                throw new ParserException(ParserException.Error.SYNTAX);
        }
    }

    /* Method finds variable and return value of it.
     * @param vname Variable name.
     * @throws ParserException error type of top-down parser.
     **/
    private double findVar(String vname) throws ParserException {
        if (!storVars.containsKey(vname)) {
            throw new ParserException(ParserException.Error.UNKNOWN_VARIABLE);
        }
        return storVars.get(vname);
    }

    /**
     * This method finds which function should be used.
     *
     * @param result result of top-down parser.
     * @throws ParserException error type of top-down parser.
     **/
    private void functions(Number result) throws ParserException {
        String function = token;
        if (ONE_PARAMETER_FUNCTIONS.contains(function)) {
            oneParameterFunctions(result, function);
        } else if (TWO_PARAMETER_FUNCTIONS.contains(function)) {
            twoParameterFunctions(result, function);
        } else if (MULTI_PARAMETER_FUNCTIONS.contains(function)) {
            multiParameterFunctions(result, function);
        } else {
            throw new ParserException(ParserException.Error.UNKNOWN_FUNCTION);
        }
    }

    /**
     * Method defines the function with one input value.
     *
     * @param result   result of top-down parser.
     * @param function one name of function.
     * @throws ParserException error type of top-down parser.
     **/
    private void oneParameterFunctions(Number result, String function) throws ParserException {
        getToken();
        sixthStepParsing(result);
        switch (function) {
            case "abs" -> result.set(Math.abs(result.get()));
            case "acos" -> result.set(valueFromMeasure(Math.acos(result.get())));
            case "asin" -> result.set(valueFromMeasure(Math.asin(result.get())));
            case "atan" -> result.set(valueFromMeasure(Math.atan(result.get())));
            case "cbrt" -> result.set(Math.cbrt(result.get()));
            case "ceil" -> result.set(Math.ceil(result.get()));
            case "cos" -> result.set(Math.cos(valueToMeasure(result.get())));
            case "exp" -> result.set(Math.exp(result.get()));
            case "factorial" -> result.set(factorial(result.get()));
            case "floor" -> result.set(Math.floor(result.get()));
            case "ln" -> result.set(Math.log(result.get()));
            case "log10" -> result.set(Math.log10(result.get()));
            case "round" -> result.set(Math.round(result.get()));
            case "sin" -> result.set(Math.sin(valueToMeasure(result.get())));
            case "sqrt" -> result.set(Math.sqrt(result.get()));
            case "tan" -> result.set(Math.tan(valueToMeasure(result.get())));
        }
    }

    /**
     * Factorial is defined only for non-negative integers in this parser.
     */
    private double factorial(double value) throws ParserException {
        if (value < 0 || value != Math.floor(value)) {
            throw new ParserException(ParserException.Error.NON_NEGATIVE_INTEGERS);
        }
        if (value > ParserException.FACTORIAL_MAX_NUMBER) {
            throw new ParserException(ParserException.Error.NUMERIC_OVERFLOW);
        }
        int intValue = (int) value;
        double result = 1.0;
        for (int i = 2; i <= intValue; i++) {
            result *= i;
        }
        return result;
    }

    /**
     * Method returns converted values.
     *
     * @param result Basic value.
     * @return converted value.
     **/
    private double valueToMeasure(double result) {
        return switch (angleUnit) {
            case DEGREE -> result * Math.PI / 180;
            case GRADIAN -> result * Math.PI / 200;
            case RADIAN -> result;
        };
    }

    /**
     * Method converts radians to configured output unit.
     *
     * @param result Basic value in radians.
     * @return converted value in active angle unit.
     **/
    private double valueFromMeasure(double result) {
        return switch (angleUnit) {
            case DEGREE -> result * 180 / Math.PI;
            case GRADIAN -> result * 200 / Math.PI;
            case RADIAN -> result;
        };
    }

    /**
     * Method defines functions with two input values.
     *
     * @param result   result of top-down parser.
     * @param function one name of function.
     * @throws ParserException error type of top-down parser.
     **/
    private void twoParameterFunctions(Number result, String function) throws ParserException {
        getToken(); // bracket
        getToken(); // number or smth like it
        firstStepParsing(result);
        if (token.equals(",")) {
            getToken();
            Number temp = new Number();
            firstStepParsing(temp);
            if (function.equals("pow")) {
                result.set(Math.pow(result.get(), temp.get()));
            } else if (function.equals("log")) {
                result.set(Math.log(temp.get()) / Math.log(result.get()));
            }
            if (token.equals(",")) {
                throw new ParserException(ParserException.Error.SYNTAX);
            } else if (!token.equals(")")) {
                throw new ParserException(ParserException.Error.UNBAL_PARENTS);
            }
            getToken();
        } else {
            throw new ParserException(ParserException.Error.SYNTAX);
        }
    }

    /**
     * Method defines functions with multiply input values.
     *
     * @param result   result of top-down parser.
     * @param function one name of function.
     * @throws ParserException error type of top-down parser.
     **/
    private void multiParameterFunctions(Number result, String function) throws ParserException {
        getToken(); // bracket
        getToken(); // get result before delimiter
        firstStepParsing(result);
        int i = 1;
        for (; ; ) {
            if (token.equals(",")) {
                getToken();
                Number temp = new Number();
                firstStepParsing(temp);
                if (function.equals("min") && result.get() > temp.get()) { // min
                    result.set(temp.get());
                } else if (function.equals("max") && result.get() < temp.get()) { // max
                    result.set(temp.get());
                } else if (function.equals("avg") || function.equals("sum")) { // sum
                    result.set(result.get() + temp.get());
                    i++;
                }
            } else if (token.equals(")")) {
                if (function.equals("avg")) {
                    result.set(result.get() / i);
                }
                getToken();
                break;
            } else {
                throw new ParserException(ParserException.Error.UNBAL_PARENTS);
            }
        }
    }

    /**
     * Method returns the next token from the input string.
     *
     * @throws ParserException error type of top-down parser.
     **/
    private void getToken() throws ParserException {
        tokenType = Types.NONE;
        token = "";
        StringBuilder strBuilder = new StringBuilder(inputString.length());
        if (currentIndex == inputString.length()) {
            return;
        }

        if (isDelimiter(inputString.charAt(currentIndex))) {
            strBuilder.append(inputString.charAt(currentIndex));
            currentIndex++;
            tokenType = Types.DELIMITER;
        } else if (Character.isLetter(inputString.charAt(currentIndex))) {
            int ctrl = 0;
            while (!isDelimiter(inputString.charAt(currentIndex))) {
                if (!Character.isLetterOrDigit(inputString.charAt(currentIndex))) {
                    throw new ParserException(ParserException.Error.UNKNOWN_EXPRESSION);
                }
                strBuilder.append(inputString.charAt(currentIndex));
                currentIndex++;
                if (currentIndex >= inputString.length()) {
                    break;
                }
                ctrl++;
                if (ctrl > ParserException.IDENTIFIER_MAX_LENGTH) {
                    throw new ParserException(ParserException.Error.IDENTIFIER_TOO_LONG);
                }
            }
            if (currentIndex < inputString.length() && inputString.charAt(currentIndex) == '(') {
                tokenType = Types.FUNCTION;
            } else {
                tokenType = Types.VARIABLE;
            }
        } else if (Character.isDigit(inputString.charAt(currentIndex))) {
            while (!isDelimiter(inputString.charAt(currentIndex))) {
                if (Character.isLetter(inputString.charAt(currentIndex))) {
                    // Stop numeric token before letters so expressions like 2pi parse as 2 * pi.
                    break;
                }
                if (!Character.isDigit(inputString.charAt(currentIndex)) && inputString.charAt(currentIndex) != '.') {
                    throw new ParserException(ParserException.Error.UNKNOWN_EXPRESSION);
                }
                strBuilder.append(inputString.charAt(currentIndex));
                currentIndex++;
                if (currentIndex >= inputString.length()) {
                    break;
                }
            }
            tokenType = Types.NUMBER;
        } else {
            throw new ParserException(ParserException.Error.UNKNOWN_EXPRESSION);
        }
        token = strBuilder.toString();
    }

    /**
     * Method defines the delimiter.
     **/
    private boolean isDelimiter(char ctr) {
        return (" +-/\\*%^=(),".indexOf(ctr) != -1);
    }

    /**
     * Detects token adjacency that should be interpreted as multiplication.
     */
    private boolean isImplicitMultiplicationToken() {
        return token.equals("(")
                || tokenType == Types.FUNCTION
                || tokenType == Types.NUMBER
                || tokenType == Types.VARIABLE;
    }

    /**
     * Types of tokens
     **/
    private enum Types {NONE, DELIMITER, VARIABLE, NUMBER, FUNCTION}
}
