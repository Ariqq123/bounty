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
    boolean discordNotifyPlace,
    boolean discordNotifyClaim,
    boolean discordNotifyCancel,
    boolean discordNotifyAdmin
) {
    public static BountyConfig fromConfig(FileConfiguration config) {
        return new BountyConfig(
            config.getLong("bounty.min-amount", 100L),
            config.getLong("bounty.max-amount", 0L),
            config.getInt("bounty.cancel-refund-percent", 80),
            config.getLong("anti-abuse.claim-cooldown-seconds-per-pair", 3600L),
            Math.max(9, config.getInt("gui.page-size", 28)),
            config.getBoolean("messages.broadcast-place", true),
            config.getBoolean("messages.broadcast-claim", true),
            config.getBoolean("discord.enabled", false),
            config.getString("discord.webhook-url", ""),
            config.getString("discord.username", "Bounty"),
            config.getString("discord.avatar-url", ""),
            config.getBoolean("discord.events.place", true),
            config.getBoolean("discord.events.claim", true),
            config.getBoolean("discord.events.cancel", true),
            config.getBoolean("discord.events.admin", true)
        );
    }

    public long refundAmount(long originalAmount) {
        return Math.round(originalAmount * (cancelRefundPercent / 100.0D));
    }
}
