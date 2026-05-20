package dev.ariqq.bounty.config;

import org.bukkit.configuration.file.FileConfiguration;

public record BountyConfig(
    long minAmount,
    long maxAmount,
    int cancelRefundPercent,
    long claimCooldownSecondsPerPair,
    int guiPageSize,
    boolean broadcastPlace,
    boolean broadcastClaim,
    boolean discordEnabled,
    String discordWebhookUrl,
    String discordUsername,
    String discordAvatarUrl,
    String discordFooterText,
    boolean discordShowTimestamp,
    int discordColorPlace,
    int discordColorClaim,
    int discordColorCancel,
    int discordColorAdmin,
    boolean discordNotifyPlace,
    boolean discordNotifyClaim,
    boolean discordNotifyCancel,
    boolean discordNotifyAdmin
) {
    public static BountyConfig fromConfig(FileConfiguration config) {
        long minAmount = Math.max(1L, config.getLong("bounty.min-amount", 100L));
        long configuredMaxAmount = config.getLong("bounty.max-amount", 0L);
        long maxAmount = configuredMaxAmount <= 0L ? 0L : Math.max(minAmount, configuredMaxAmount);
        int refundPercent = clamp(config.getInt("bounty.cancel-refund-percent", 80), 0, 100);
        int guiPageSize = clamp(config.getInt("gui.page-size", 28), 9, 45);
        return new BountyConfig(
            minAmount,
            maxAmount,
            refundPercent,
            Math.max(0L, config.getLong("anti-abuse.claim-cooldown-seconds-per-pair", 3600L)),
            guiPageSize,
            config.getBoolean("messages.broadcast-place", true),
            config.getBoolean("messages.broadcast-claim", true),
            config.getBoolean("discord.enabled", false),
            config.getString("discord.webhook-url", ""),
            config.getString("discord.username", "Bounty"),
            config.getString("discord.avatar-url", ""),
            config.getString("discord.embed.footer-text", "Bounty"),
            config.getBoolean("discord.embed.show-timestamp", true),
            parseColor(config.getString("discord.embed.colors.place", "#F1C40F"), 0xF1C40F),
            parseColor(config.getString("discord.embed.colors.claim", "#2ECC71"), 0x2ECC71),
            parseColor(config.getString("discord.embed.colors.cancel", "#E74C3C"), 0xE74C3C),
            parseColor(config.getString("discord.embed.colors.admin", "#3498DB"), 0x3498DB),
            config.getBoolean("discord.events.place", true),
            config.getBoolean("discord.events.claim", true),
            config.getBoolean("discord.events.cancel", true),
            config.getBoolean("discord.events.admin", true)
        );
    }

    public long refundAmount(long originalAmount) {
        return Math.round(originalAmount * (cancelRefundPercent / 100.0D));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseColor(String input, int fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        String normalized = input.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        } else if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        try {
            return Integer.parseInt(normalized, 16);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
