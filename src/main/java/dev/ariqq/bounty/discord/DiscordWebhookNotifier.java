package dev.ariqq.bounty.discord;

import dev.ariqq.bounty.config.BountyConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class DiscordWebhookNotifier implements BountyNotifier {
    private static final int COLOR_GOLD = 0xF1C40F;
    private static final int COLOR_GREEN = 0x2ECC71;
    private static final int COLOR_RED = 0xE74C3C;
    private static final int COLOR_BLUE = 0x3498DB;

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
        sendEmbed(
            "Bounty Placed",
            actor + " added a bounty on " + targetName + ".",
            adminAction ? COLOR_BLUE : COLOR_GOLD,
            """
            [{"name":"Target","value":"%s","inline":true},
            {"name":"Added Amount","value":"%d","inline":true},
            {"name":"Total Pool","value":"%d","inline":true}]
            """.formatted(escapeJson(targetName), amount, totalPool)
        );
    }

    @Override
    public void notifyBountyCancelled(String placerName, String targetName, long refundAmount) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyCancel()) {
            return;
        }
        sendEmbed(
            "Bounty Cancelled",
            placerName + " cancelled their bounty contribution.",
            COLOR_RED,
            """
            [{"name":"Target","value":"%s","inline":true},
            {"name":"Refunded","value":"%d","inline":true}]
            """.formatted(escapeJson(targetName), refundAmount)
        );
    }

    @Override
    public void notifyBountyClaimed(String killerName, String targetName, long totalAmount, int sourceCount) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyClaim()) {
            return;
        }
        sendEmbed(
            "Bounty Claimed",
            killerName + " claimed the bounty reward.",
            COLOR_GREEN,
            """
            [{"name":"Target","value":"%s","inline":true},
            {"name":"Reward","value":"%d","inline":true},
            {"name":"Contributions","value":"%d","inline":true}]
            """.formatted(escapeJson(targetName), totalAmount, sourceCount)
        );
    }

    @Override
    public void notifyAdminTargetRemoved(String targetName, int removedContributions) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyAdmin()) {
            return;
        }
        sendEmbed(
            "Admin Action",
            "An admin removed active bounty contributions.",
            COLOR_BLUE,
            """
            [{"name":"Target","value":"%s","inline":true},
            {"name":"Removed Contributions","value":"%d","inline":true}]
            """.formatted(escapeJson(targetName), removedContributions)
        );
    }

    @Override
    public void notifyAdminRefund(String targetName, long refundedAmount, int refundedContributions) {
        BountyConfig config = configSupplier.get();
        if (!config.discordNotifyAdmin()) {
            return;
        }
        sendEmbed(
            "Admin Refund",
            "An admin refunded bounty contributions.",
            COLOR_BLUE,
            """
            [{"name":"Target","value":"%s","inline":true},
            {"name":"Refunded Amount","value":"%d","inline":true},
            {"name":"Refunded Contributions","value":"%d","inline":true}]
            """.formatted(escapeJson(targetName), refundedAmount, refundedContributions)
        );
    }

    private void sendEmbed(String title, String description, int color, String fieldsJson) {
        BountyConfig config = configSupplier.get();
        String webhookUrl = config.discordWebhookUrl() == null ? "" : config.discordWebhookUrl().trim();
        if (!config.discordEnabled() || webhookUrl.isEmpty()) {
            return;
        }

        String payload = buildPayload(config, title, description, color, fieldsJson);
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

    private String buildPayload(BountyConfig config, String title, String description, int color, String fieldsJson) {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        builder.append("\"embeds\":[{");
        builder.append("\"title\":\"").append(escapeJson(title)).append("\",");
        builder.append("\"description\":\"").append(escapeJson(description)).append("\",");
        builder.append("\"color\":").append(color).append(",");
        builder.append("\"fields\":").append(fieldsJson.replace('\n', ' ').trim());
        builder.append("}]");
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
