package dev.nklip.javacraft.weather.web;

import dev.nklip.javacraft.weather.domain.CityWeather;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import java.util.List;

/**
 * Type-safe Qute templates.
 *
 * <p>{@code @CheckedTemplate} binds each native method to {@code src/main/resources/templates/}
 * by name and validates the template's expressions against the declared parameter types at build
 * time, so a renamed field breaks the build rather than the page.
 */
@CheckedTemplate
public class Templates {

    private Templates() {
    }

    /** Renders {@code templates/weather.html}. */
    public static native TemplateInstance weather(List<CityWeather> forecasts);
}
