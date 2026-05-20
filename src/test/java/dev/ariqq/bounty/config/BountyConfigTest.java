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

    @Test
    void refundAmountUsesExactIntegerRoundingForLargeValues() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bounty.cancel-refund-percent", 33);

        BountyConfig config = BountyConfig.fromConfig(configuration);

        long refund = config.refundAmount(BountyConfig.MAX_SAFE_ECONOMY_AMOUNT);

        Assertions.assertEquals(2_972_375_754_064_527L, refund);
    }

    @Test
    void fromConfigDefaultsToSqliteStorage() {
        BountyConfig config = BountyConfig.fromConfig(new YamlConfiguration());

        Assertions.assertEquals("sqlite", config.storageType());
        Assertions.assertFalse(config.useMysqlStorage());
        Assertions.assertEquals("127.0.0.1", config.mysqlHost());
        Assertions.assertEquals(3306, config.mysqlPort());
        Assertions.assertEquals("bounty", config.mysqlDatabase());
    }

    @Test
    void fromConfigReadsMysqlStorageSettings() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("storage.type", "mysql");
        configuration.set("storage.mysql.host", "db.example.com");
        configuration.set("storage.mysql.port", 3307);
        configuration.set("storage.mysql.database", "bounty_live");
        configuration.set("storage.mysql.username", "bounty_user");
        configuration.set("storage.mysql.password", "secret");
        configuration.set("storage.mysql.use-ssl", true);

        BountyConfig config = BountyConfig.fromConfig(configuration);

        Assertions.assertTrue(config.useMysqlStorage());
        Assertions.assertEquals("mysql", config.storageType());
        Assertions.assertEquals("db.example.com", config.mysqlHost());
        Assertions.assertEquals(3307, config.mysqlPort());
        Assertions.assertEquals("bounty_live", config.mysqlDatabase());
        Assertions.assertEquals("bounty_user", config.mysqlUsername());
        Assertions.assertEquals("secret", config.mysqlPassword());
        Assertions.assertTrue(config.mysqlUseSsl());
    }
}
