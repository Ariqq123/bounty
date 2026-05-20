package dev.ariqq.bounty.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BountyConfigTest {
    @Test
    void fromConfigClampsRefundPercentPageSizeAndCooldown() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bounty.min-amount", -10L);
        configuration.set("bounty.max-amount", 5L);
        configuration.set("bounty.cancel-refund-percent", 150);
        configuration.set("gui.page-size", 100);
        configuration.set("anti-abuse.claim-cooldown-seconds-per-pair", -30L);

        BountyConfig config = BountyConfig.fromConfig(configuration);

        Assertions.assertEquals(1L, config.minAmount());
        Assertions.assertEquals(5L, config.maxAmount());
        Assertions.assertEquals(100, config.cancelRefundPercent());
        Assertions.assertEquals(45, config.guiPageSize());
        Assertions.assertEquals(0L, config.claimCooldownSecondsPerPair());
        Assertions.assertEquals(1_000L, config.refundAmount(1_000L));
    }

    @Test
    void fromConfigRaisesPositiveMaxAmountToAtLeastMinimum() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bounty.min-amount", 500L);
        configuration.set("bounty.max-amount", 200L);

        BountyConfig config = BountyConfig.fromConfig(configuration);

        Assertions.assertEquals(500L, config.minAmount());
        Assertions.assertEquals(500L, config.maxAmount());
    }

    @Test
    void fromConfigClampsAmountsToSafeEconomyPrecision() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bounty.min-amount", Long.MAX_VALUE);
        configuration.set("bounty.max-amount", Long.MAX_VALUE);

        BountyConfig config = BountyConfig.fromConfig(configuration);

        Assertions.assertEquals(BountyConfig.MAX_SAFE_ECONOMY_AMOUNT, config.minAmount());
        Assertions.assertEquals(BountyConfig.MAX_SAFE_ECONOMY_AMOUNT, config.maxAmount());
    }
}
