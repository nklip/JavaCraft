package dev.nklip.javacraft.weather.assistant;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import dev.nklip.javacraft.weather.domain.CityWeather;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Answers weather questions with Claude, grounded in the observations already on the page. */
@ApplicationScoped
public class ClaudeWeatherAssistant {

    private static final Logger LOG = Logger.getLogger(ClaudeWeatherAssistant.class);

    private static final Map<String, OutputConfig.Effort> EFFORTS = Map.of(
            "low", OutputConfig.Effort.LOW,
            "medium", OutputConfig.Effort.MEDIUM,
            "high", OutputConfig.Effort.HIGH,
            "xhigh", OutputConfig.Effort.XHIGH,
            "max", OutputConfig.Effort.MAX);

    private final String model;
    private final long maxTokens;
    private final OutputConfig.Effort effort;

    /** Null exactly when no API key is configured — see {@link #configured()}. */
    private final MessageSender sender;

    /* MicroProfile Config supports Optional injection so an absent API key stays non-fatal. */
    @Inject
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public ClaudeWeatherAssistant(
            @ConfigProperty(name = "anthropic.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "anthropic.model") String model,
            @ConfigProperty(name = "anthropic.max-tokens") long maxTokens,
            @ConfigProperty(name = "anthropic.effort") String effort) {
        this(apiKey, model, maxTokens, effort, ClaudeWeatherAssistant::okHttpSender);
    }

    /** Visible for testing: mirrors config optionality and lets a mock replace the network. */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    ClaudeWeatherAssistant(
            Optional<String> apiKey,
            String model,
            long maxTokens,
            String effort,
            Function<String, MessageSender> senderFactory) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = parseEffort(effort);
        this.sender = apiKey.filter(key -> !key.isBlank())
                .map(senderFactory)
                .orElse(null);
    }

    private static MessageSender okHttpSender(String apiKey) {
        var client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        return params -> client.messages().create(params);
    }

    /**
     * Maps the configured effort name onto the SDK constant.
     *
     * <p>{@code Effort.of(String)} would accept anything and only fail as a 400 at request time;
     * an explicit table turns a typo in {@code application.properties} into a startup failure.
     */
    static OutputConfig.Effort parseEffort(String effort) {
        OutputConfig.Effort parsed = EFFORTS.get(effort.strip().toLowerCase(Locale.ROOT));
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "unknown anthropic.effort '" + effort + "', expected one of " + EFFORTS.keySet());
        }
        return parsed;
    }

    /**
     * Whether an API key was supplied. When false the dashboard renders the assistant panel
     * disabled and never calls {@link #answer}.
     */
    public boolean configured() {
        return sender != null;
    }

    /** Never throws: every failure becomes a {@link WeatherAnswer} the page can render. */
    public WeatherAnswer answer(String question, List<CityWeather> forecasts) {
        if (!configured()) {
            return WeatherAnswer.disabled();
        }
        try {
            Message response = sender.send(request(question, forecasts));
            if (refused(response)) {
                // Expected outcome, not a fault — log at INFO without the question text.
                LOG.info("Claude declined to answer a dashboard question");
                return WeatherAnswer.refused();
            }
            String text = textOf(response);
            return text.isBlank() ? WeatherAnswer.failed() : WeatherAnswer.answered(text);
        } catch (RuntimeException e) {
            // Message only: a stack trace for a routine timeout is noise, and the request
            // carries the user's question.
            LOG.warnf("Claude request failed: %s", e.toString());
            return WeatherAnswer.failed();
        }
    }

    private MessageCreateParams request(String question, List<CityWeather> forecasts) {
        return MessageCreateParams.builder()
                .model(model)
                // Thinking is on by default on this model and counts against maxTokens, so the
                // ceiling covers reasoning plus the answer, not the answer alone.
                .maxTokens(maxTokens)
                .system(WeatherPrompt.SYSTEM)
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .addUserMessage(WeatherPrompt.userMessage(question, forecasts))
                .build();
    }

    /** A refusal is a successful HTTP 200 with an empty body — check before reading content. */
    private static boolean refused(Message response) {
        return response.stopReason().filter(StopReason.REFUSAL::equals).isPresent();
    }

    private static String textOf(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text().strip())
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }
}
