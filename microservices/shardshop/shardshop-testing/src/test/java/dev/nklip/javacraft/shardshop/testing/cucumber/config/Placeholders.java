package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code {repeat:text:count}} in a feature file cell is the text repeated count times, for long boundary values. */
public final class Placeholders {
    private static final Pattern REPEAT = Pattern.compile("\\{repeat:([^:}]+):(\\d+)}");

    private Placeholders() {
    }

    public static String repeat(String text) {
        Matcher matcher = REPEAT.matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(expanded,
                    Matcher.quoteReplacement(matcher.group(1).repeat(Integer.parseInt(matcher.group(2)))));
        }
        return matcher.appendTail(expanded).toString();
    }
}
