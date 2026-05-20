package dev.ariqq.bounty.discord;

import dev.ariqq.bounty.config.BountyConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class DiscordWebhookNotifier implements BountyNotifier {
    private final HttpClient httpClient;
    private final Supplier<BountyConfig> configSupplier;
    private final Logger logger;

    public DiscordWebhookNotifier(Supplier<BountyConfig> configSupplier, Logger logger) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.configSupplier = configSupplier;
        this.logger = logger;
    }

    @Override
    public void notifyBountyPlaced(String placerName, String targetName, long amount, long totalPool, boolean adminAction) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyPlace()) {
            return;
        }
        String actor = adminAction ? "[ADMIN] " + placerName : placerName;
        sendMessage("Bounty placed: **" + actor + "** added **" + amount + "** on **" + targetName + "**. Total pool: **" + totalPool + "**.");
    }

    @Override
    public void notifyBountyCancelled(String placerName, String targetName, long refundAmount) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyCancel()) {
            return;
        }
        sendMessage("Bounty cancelled: **" + placerName + "** cancelled their bounty on **" + targetName + "** and received **" + refundAmount + "** back.");
    }

    @Override
    public void notifyBountyClaimed(String killerName, String targetName, long totalAmount, int sourceCount) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyClaim()) {
            return;
        }
        sendMessage("Bounty claimed: **" + killerName + "** killed **" + targetName + "** and earned **" + totalAmount + "** from **" + sourceCount + "** contribution(s).");
    }

    @Override
    public void notifyAdminTargetRemoved(String targetName, int removedContributions) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyAdmin()) {
            return;
        }
        sendMessage("Admin action: removed **" + removedContributions + "** active contribution(s) from **" + targetName + "**.");
    }

    @Override
    public void notifyAdminRefund(String targetName, long refundedAmount, int refundedContributions) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyAdmin()) {
            return;
        }
        sendMessage("Admin action: refunded **" + refundedAmount + "** across **" + refundedContributions + "** contribution(s) for **" + targetName + "**.");
    }

    private void sendMessage(String content) {
        BountyConfig config = configSupplier.get();
        String webhookUrl = config.discordWebhookUrl() == null ? "" : config.discordWebhookUrl().trim();
        if (!config.discordEnabled() || webhookUrl.isEmpty()) {
            return;
        }

        String payload = buildPayload(config, content);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .thenAccept(response -> {
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    logger.warning("Discord webhook returned status " + status + ".");
                }
            })
            .exceptionally(throwable -> {
                logger.warning("Discord webhook request failed: " + throwable.getMessage());
                return null;
            });
    }

    private String buildPayload(BountyConfig config, String content) {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        builder.append("\"content\":\"").append(escapeJson(content)).append("\"");
        appendOptional(builder, "username", config.discordUsername());
        appendOptional(builder, "avatar_url", config.discordAvatarUrl());
        builder.append("}");
        return builder.toString();
    }

    private void appendOptional(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(",\"").append(key).append("\":\"").append(escapeJson(value.trim())).append("\"");
    }

    private String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
