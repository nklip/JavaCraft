package dev.nklip.javacraft.weather.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ContentBlock;
import dev.nklip.javacraft.weather.domain.Cities;
import dev.nklip.javacraft.weather.domain.CityWeather;
import dev.nklip.javacraft.weather.domain.WeatherCondition;
import dev.nklip.javacraft.weather.domain.WeatherSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The network call is behind {@link MessageSender}, so these are plain unit tests — no API key,
 * no HTTP, no CDI container.
 */
class ClaudeWeatherAssistantTest {

    private static final List<CityWeather> FORECASTS = List.of(
            CityWeather.of(Cities.GLASGOW, new WeatherSnapshot(15.3, 91, 13.7,
                    WeatherCondition.DRIZZLE, LocalDateTime.of(2026, 8, 4, 7, 0))));

    private final MessageSender sender = mock(MessageSender.class);

    private ClaudeWeatherAssistant assistantWithKey() {
        return new ClaudeWeatherAssistant(
                Optional.of("sk-ant-test"), "claude-sonnet-5", 16000L, "medium", _ -> sender);
    }

    /**
     * A stubbed reply.
     *
     * <p>{@code Message} is mocked rather than built: its builder demands {@code usage},
     * {@code id} and a chain of other required fields this class never reads, and stubbing the
     * two accessors under test states the intent more directly than filling them in.
     */
    private static Message reply(String text, StopReason stopReason) {
        Message message = mock(Message.class);
        when(message.stopReason()).thenReturn(Optional.of(stopReason));
        when(message.content()).thenReturn(List.of(ContentBlock.ofText(
                TextBlock.builder().text(text).citations(Optional.empty()).build())));
        return message;
    }

    @Test
    void reportsUnconfiguredWhenNoKeyIsPresent() {
        var assistant = new ClaudeWeatherAssistant(
                Optional.empty(), "claude-sonnet-5", 16000L, "medium", _ -> sender);

        assertFalse(assistant.configured());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void treatsABlankKeyAsUnconfigured(String key) {
        var assistant = new ClaudeWeatherAssistant(
                Optional.of(key), "claude-sonnet-5", 16000L, "medium", _ -> sender);

        assertFalse(assistant.configured());
    }

    @Test
    void neverCallsTheApiWhenUnconfigured() {
        var assistant = new ClaudeWeatherAssistant(
                Optional.empty(), "claude-sonnet-5", 16000L, "medium", _ -> sender);

        WeatherAnswer answer = assistant.answer("Is it raining?", FORECASTS);

        assertEquals(WeatherAnswer.Status.DISABLED, answer.status());
        verify(sender, never()).send(any());
    }

    @Test
    void reportsConfiguredWhenAKeyIsPresent() {
        assertTrue(assistantWithKey().configured());
    }

    @Test
    void returnsClaudesText() {
        Message response = reply("Glasgow is drizzly.", StopReason.END_TURN);
        when(sender.send(any())).thenReturn(response);

        WeatherAnswer answer = assistantWithKey().answer("Where is it raining?", FORECASTS);

        assertEquals(WeatherAnswer.Status.ANSWERED, answer.status());
        assertEquals("Glasgow is drizzly.", answer.text());
    }

    @Test
    void sendsTheGroundedPromptAndConfiguredModel() {
        Message response = reply("ok", StopReason.END_TURN);
        when(sender.send(any())).thenReturn(response);
        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);

        assistantWithKey().answer("Where is it raining?", FORECASTS);

        verify(sender).send(captor.capture());
        MessageCreateParams sent = captor.getValue();
        assertEquals("claude-sonnet-5", sent.model().toString());
        assertEquals(16000L, sent.maxTokens());
        assertTrue(sent.system().isPresent());
    }

    @Test
    void mapsARefusalToItsOwnStatusRatherThanAnAnswer() {
        Message response = reply("", StopReason.REFUSAL);
        when(sender.send(any())).thenReturn(response);

        WeatherAnswer answer = assistantWithKey().answer("Something disallowed", FORECASTS);

        assertEquals(WeatherAnswer.Status.REFUSED, answer.status());
    }

    @Test
    void treatsAnEmptyResponseAsAFailureRatherThanAnEmptyAnswer() {
        Message response = reply("   ", StopReason.END_TURN);
        when(sender.send(any())).thenReturn(response);

        WeatherAnswer answer = assistantWithKey().answer("Where is it raining?", FORECASTS);

        assertEquals(WeatherAnswer.Status.FAILED, answer.status());
    }

    @Test
    void convertsUpstreamFailuresIntoARenderableAnswer() {
        when(sender.send(any())).thenThrow(new IllegalStateException("connection reset"));

        WeatherAnswer answer = assistantWithKey().answer("Where is it raining?", FORECASTS);

        assertEquals(WeatherAnswer.Status.FAILED, answer.status());
        assertFalse(answer.text().contains("connection reset"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "MEDIUM", " high ", "xhigh", "max"})
    void acceptsEveryEffortLevelCaseInsensitively(String effort) {
        assertEquals(OutputConfig.Effort.of(effort.strip().toLowerCase(java.util.Locale.ROOT)),
                ClaudeWeatherAssistant.parseEffort(effort));
    }

    /**
     * Covers the CDI constructor and its real SDK client factory. Building the client is local
     * wiring — no network call happens until a message is sent — so a dummy key is enough to
     * prove the production path assembles.
     */
    @Test
    void productionConstructorBuildsARealClientFromTheKey() {
        var assistant = new ClaudeWeatherAssistant(
                Optional.of("sk-ant-not-a-real-key"), "claude-sonnet-5", 16000L, "medium");

        assertTrue(assistant.configured());
    }

    @Test
    void productionConstructorStaysUnconfiguredWithoutAKey() {
        var assistant = new ClaudeWeatherAssistant(
                Optional.empty(), "claude-sonnet-5", 16000L, "medium");

        assertFalse(assistant.configured());
    }

    @Test
    void rejectsAnUnknownEffortAtStartupRatherThanAtRequestTime() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ClaudeWeatherAssistant.parseEffort("turbo"));

        assertTrue(thrown.getMessage().contains("turbo"));
    }
}
