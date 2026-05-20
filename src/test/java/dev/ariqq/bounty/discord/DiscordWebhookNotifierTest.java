package dev.ariqq.bounty.discord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DiscordWebhookNotifierTest {
    @Test
    void escapeJsonHandlesNull() {
        Assertions.assertEquals("", DiscordWebhookNotifier.escapeJson(null));
    }

    @Test
    void escapeJsonEscapesJsonControlCharacters() {
        String input = "\"\\\b\f\n\r\t";

        Assertions.assertEquals("\\\"\\\\\\b\\f\\n\\r\\t", DiscordWebhookNotifier.escapeJson(input));
    }

    @Test
    void escapeJsonUnicodeEscapesOtherLowControlCharacters() {
        String input = "\u0001\u001f";

        Assertions.assertEquals("\\u0001\\u001f", DiscordWebhookNotifier.escapeJson(input));
    }
}
