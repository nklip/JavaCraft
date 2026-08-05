package dev.nklip.javacraft.weather.assistant;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * The single point where this module touches the Anthropic SDK over the network.
 *
 * <p>Isolating the call behind a one-method interface keeps {@link ClaudeWeatherAssistant}'s
 * prompt-building and response-mapping logic unit-testable with a mock, without a live API key.
 */
@FunctionalInterface
public interface MessageSender {

    Message send(MessageCreateParams params);
}
