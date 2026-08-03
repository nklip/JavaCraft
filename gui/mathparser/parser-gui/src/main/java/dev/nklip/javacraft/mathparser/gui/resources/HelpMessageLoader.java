package dev.nklip.javacraft.mathparser.gui.resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Loads help text from resources so GUI code stays maintainable and ready for i18n.
 */
public final class HelpMessageLoader {
    private static final String HELP_RESOURCE_PATH = "/help/help-message.txt";
    private static final String FALLBACK_MESSAGE = "Help content is unavailable.";

    private HelpMessageLoader() {
    }

    /**
     * Returns help text from classpath, with a safe fallback for missing/corrupted resources.
     */
    public static String loadHelpMessage() {
        return loadHelpMessage(() -> HelpMessageLoader.class.getResourceAsStream(HELP_RESOURCE_PATH));
    }

    // for testing
    static String loadHelpMessage(Supplier<InputStream> helpResourceSupplier) {
        try (InputStream inputStream = helpResourceSupplier.get()) {
            if (inputStream == null) {
                return FALLBACK_MESSAGE;
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return FALLBACK_MESSAGE;
        }
    }
}
