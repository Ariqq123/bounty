package dev.ariqq.bounty.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BountyConfigTest {
    @Test
    void fromConfigClampsRefundPercentPageSizeAndCooldown() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bounty.cancel-refund-percent", 150);
        configuration.set("gui.page-size", 100);
        configuration.set("anti-abuse.claim-cooldown-seconds-per-pair", -30L);

        BountyConfig config = BountyConfig.fromConfig(configuration);

        Assertions.assertEquals(100, config.cancelRefundPercent());
        Assertions.assertEquals(45, config.guiPageSize());
        Assertions.assertEquals(0L, config.claimCooldownSecondsPerPair());
        Assertions.assertEquals(1_000L, config.refundAmount(1_000L));
    }
}
