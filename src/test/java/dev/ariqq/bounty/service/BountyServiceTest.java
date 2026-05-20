package dev.ariqq.bounty.service;

import dev.ariqq.bounty.config.BountyConfig;
import dev.ariqq.bounty.discord.BountyNotifier;
import dev.ariqq.bounty.model.BountyClaim;
import dev.ariqq.bounty.model.BountyContribution;
import dev.ariqq.bounty.model.BountyTargetSummary;
import dev.ariqq.bounty.model.ContributionStatus;
import dev.ariqq.bounty.model.KnownPlayer;
import dev.ariqq.bounty.model.ServiceResult;
import dev.ariqq.bounty.storage.BountyRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BountyServiceTest {
    @Test
    void placeMergesExistingContribution() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        ServiceResult first = service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 250);
        ServiceResult second = service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 500);

        Assertions.assertTrue(first.success());
        Assertions.assertTrue(second.success());
        Assertions.assertEquals(750L, repository.getUnsafeTotal(target));
        Assertions.assertEquals(1, repository.getUnsafeByTarget(target).size());
        Assertions.assertEquals(2, notifier.placedEvents);
    }

    @Test
    void cancelReturnsConfiguredRefund() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 1000);
        ServiceResult result = service.cancelOwnBounty(placer, "Hunter", new KnownPlayer(target, "Target"));

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(4_800D, economy.balance(placer));
        Assertions.assertEquals(1, notifier.cancelEvents);
    }

    @Test
    void cancelRejectsAdminFundedAttributedContribution() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        KnownPlayer player = new KnownPlayer(playerUuid, "Hunter");
        KnownPlayer target = new KnownPlayer(targetUuid, "Target");

        ServiceResult added = service.adminAddBounty(target, 500L, player);
        ServiceResult cancelled = service.cancelOwnBounty(playerUuid, "Hunter", target);

        Assertions.assertTrue(added.success());
        Assertions.assertFalse(cancelled.success());
        Assertions.assertEquals("Admin-funded bounty contributions cannot be cancelled by players.", cancelled.message());
        Assertions.assertEquals(0D, economy.balance(playerUuid));
    }

    @Test
    void cancelFailedRefundKeepsContributionActive() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, new FakeNotifier(), BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        economy.failNextDepositFor(placer);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 1000);

        ServiceResult result = service.cancelOwnBounty(placer, "Hunter", new KnownPlayer(target, "Target"));

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(1, repository.getUnsafeByTarget(target).size());
        Assertions.assertEquals(4_000D, economy.balance(placer));
    }

    @Test
    void cancelStatusMismatchRollsBackRefund() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.failNextTransition = true;
        FakeEconomy economy = new FakeEconomy();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, new FakeNotifier(), BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 1000);

        ServiceResult result = service.cancelOwnBounty(placer, "Hunter", new KnownPlayer(target, "Target"));

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(4_000D, economy.balance(placer));
        Assertions.assertEquals(1, repository.getUnsafeByTarget(target).size());
    }

    @Test
    void claimDatabaseFailureCompensatesPaidReward() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.failOnRecordClaim = true;
        FakeEconomy economy = new FakeEconomy();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, new FakeNotifier(), BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        economy.setBalance(killer, 100);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 1000);

        ClaimResult result = service.claimIfEligible(killer, "Slayer", target, "Target");

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(100D, economy.balance(killer));
        Assertions.assertEquals(1, repository.getUnsafeByTarget(target).size());
    }

    @Test
    void claimStatusMismatchCompensatesPaidReward() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.failFinalizeClaim = true;
        FakeEconomy economy = new FakeEconomy();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, new FakeNotifier(), BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        economy.setBalance(killer, 100);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 1000);

        ClaimResult result = service.claimIfEligible(killer, "Slayer", target, "Target");

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(100D, economy.balance(killer));
        Assertions.assertEquals(1, repository.getUnsafeByTarget(target).size());
    }

    @Test
    void claimSendsDiscordNotificationOnSuccess() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        economy.setBalance(killer, 100);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 1000);

        ClaimResult result = service.claimIfEligible(killer, "Slayer", target, "Target");

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(1, notifier.claimEvents);
    }

    @Test
    void activeTargetCountTracksUniqueTargets() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID placerOne = UUID.randomUUID();
        UUID placerTwo = UUID.randomUUID();
        UUID targetOne = UUID.randomUUID();
        UUID targetTwo = UUID.randomUUID();

        economy.setBalance(placerOne, 10_000);
        economy.setBalance(placerTwo, 10_000);
        service.placeBounty(placerOne, "HunterOne", new KnownPlayer(targetOne, "TargetOne"), 250);
        service.placeBounty(placerTwo, "HunterTwo", new KnownPlayer(targetOne, "TargetOne"), 300);
        service.placeBounty(placerOne, "HunterOne", new KnownPlayer(targetTwo, "TargetTwo"), 400);

        Assertions.assertEquals(2, service.countActiveTargets());
    }

    @Test
    void adminAddRespectsConfiguredMaximum() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::limitedConfig);
        UUID target = UUID.randomUUID();

        ServiceResult result = service.adminAddBounty(new KnownPlayer(target, "Target"), 1_500L, null);

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(0L, repository.getUnsafeTotal(target));
        Assertions.assertEquals(0, notifier.placedEvents);
    }

    @Test
    void adminAddRejectsTargetAsAttributedPlacer() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID target = UUID.randomUUID();
        KnownPlayer samePlayer = new KnownPlayer(target, "Target");

        ServiceResult result = service.adminAddBounty(samePlayer, 500L, samePlayer);

        Assertions.assertFalse(result.success());
        Assertions.assertEquals("Admin bounty placer cannot be the same as the target.", result.message());
        Assertions.assertEquals(0L, repository.getUnsafeTotal(target));
        Assertions.assertEquals(0, notifier.placedEvents);
    }

    @Test
    void adminRefundConsoleContributionReportsClosedWithoutFunds() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID target = UUID.randomUUID();

        ServiceResult added = service.adminAddBounty(new KnownPlayer(target, "Target"), 500L, null);
        ServiceResult refunded = service.adminRefundTarget(new KnownPlayer(target, "Target"), null);

        Assertions.assertTrue(added.success());
        Assertions.assertTrue(refunded.success());
        Assertions.assertEquals("Closed 1 contribution(s). No player funds were refunded.", refunded.message());
        Assertions.assertEquals(0L, repository.getUnsafeTotal(target));
    }

    @Test
    void adminRemoveRejectsActivePlayerFundedContributions() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID target = UUID.randomUUID();
        UUID placer = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 500L);

        ServiceResult removed = service.adminRemoveTarget(new KnownPlayer(target, "Target"));

        Assertions.assertFalse(removed.success());
        Assertions.assertEquals(
            "Cannot remove active player-funded contributions from Target. Use /bounty admin refund Target instead.",
            removed.message()
        );
        Assertions.assertEquals(500L, repository.getUnsafeTotal(target));
    }

    @Test
    void adminRemoveAllowsAdminFundedOnlyContributions() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID target = UUID.randomUUID();

        ServiceResult added = service.adminAddBounty(new KnownPlayer(target, "Target"), 500L, null);
        ServiceResult removed = service.adminRemoveTarget(new KnownPlayer(target, "Target"));

        Assertions.assertTrue(added.success());
        Assertions.assertTrue(removed.success());
        Assertions.assertEquals("Removed 1 non-refundable contribution(s) from Target.", removed.message());
        Assertions.assertEquals(0L, repository.getUnsafeTotal(target));
    }

    @Test
    void adminRefundReportsPartialFailuresAndLeavesUnprocessedContributionActive() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID target = UUID.randomUUID();
        UUID placerOne = UUID.randomUUID();
        UUID placerTwo = UUID.randomUUID();

        economy.setBalance(placerOne, 5_000);
        economy.setBalance(placerTwo, 5_000);
        service.placeBounty(placerOne, "HunterOne", new KnownPlayer(target, "Target"), 300L);
        service.placeBounty(placerTwo, "HunterTwo", new KnownPlayer(target, "Target"), 500L);
        economy.failNextDepositFor(placerOne);

        ServiceResult refunded = service.adminRefundTarget(new KnownPlayer(target, "Target"), null);

        Assertions.assertTrue(refunded.success());
        Assertions.assertEquals(
            "Closed 1 contribution(s). Refunded 500 across 1 player contribution(s). 1 contribution(s) could not be processed and remain active.",
            refunded.message()
        );
        Assertions.assertEquals(300L, repository.getUnsafeTotal(target));
    }

    @Test
    void playerContributionsExcludeAdminFundedAttributions() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID playerUuid = UUID.randomUUID();
        KnownPlayer player = new KnownPlayer(playerUuid, "Hunter");

        service.adminAddBounty(new KnownPlayer(UUID.randomUUID(), "Target"), 500L, player);

        Assertions.assertTrue(service.getPlayerContributions(playerUuid).isEmpty());
    }

    @Test
    void targetSummaryCountsUniqueContributorsAcrossFundingSources() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        KnownPlayer target = new KnownPlayer(targetUuid, "Target");
        KnownPlayer samePlayer = new KnownPlayer(playerUuid, "Hunter");

        economy.setBalance(playerUuid, 10_000);
        ServiceResult placed = service.placeBounty(playerUuid, "Hunter", target, 400L);
        ServiceResult adminAdded = service.adminAddBounty(target, 600L, samePlayer);

        Assertions.assertTrue(placed.success());
        Assertions.assertTrue(adminAdded.success());
        BountyTargetSummary summary = service.getTargetSummary(targetUuid).orElseThrow();
        Assertions.assertEquals(1_000L, summary.totalAmount());
        Assertions.assertEquals(1, summary.contributorCount());
    }

    @Test
    void targetSummaryUsesMostRecentlyUpdatedTargetName() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID targetUuid = UUID.randomUUID();
        UUID placerOne = UUID.randomUUID();
        UUID placerTwo = UUID.randomUUID();

        economy.setBalance(placerOne, 10_000);
        economy.setBalance(placerTwo, 10_000);
        service.placeBounty(placerOne, "HunterOne", new KnownPlayer(targetUuid, "Zed"), 300L);
        service.placeBounty(placerTwo, "HunterTwo", new KnownPlayer(targetUuid, "Aaron"), 400L);
        service.placeBounty(placerOne, "HunterOne", new KnownPlayer(targetUuid, "Aaron"), 200L);

        BountyTargetSummary summary = service.getTargetSummary(targetUuid).orElseThrow();
        Assertions.assertEquals("Aaron", summary.targetName());
        Assertions.assertEquals(900L, summary.totalAmount());
    }

    @Test
    void listActiveTargetsReturnsEmptyWhenPageOffsetWouldOverflow() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 10_000);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 250);

        List<BountyTargetSummary> page = service.listActiveTargets(Integer.MAX_VALUE, 28);

        Assertions.assertTrue(page.isEmpty());
    }

    @Test
    void discordTestNotificationCanBeTriggered() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::testConfig);

        ServiceResult result = service.sendDiscordTest("Console");

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(1, notifier.testEvents);
    }

    @Test
    void discordTestFailsWhenDisabled() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::disabledDiscordConfig);

        ServiceResult result = service.sendDiscordTest("Console");

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(0, notifier.testEvents);
    }

    @Test
    void discordTestFailsWhenWebhookUrlIsInvalid() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::invalidDiscordConfig);

        ServiceResult result = service.sendDiscordTest("Console");

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(0, notifier.testEvents);
    }

    @Test
    void discordTestFailsWhenWebhookUrlIsRelative() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        FakeNotifier notifier = new FakeNotifier();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, notifier, BountyServiceTest::relativeDiscordConfig);

        ServiceResult result = service.sendDiscordTest("Console");

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(0, notifier.testEvents);
    }

    private static BountyConfig testConfig() {
        return new BountyConfig(100, 0, 80, 3600, 28, false, false, true, "https://example.test/webhook", "Bounty", "", "Bounty", true, 0xF1C40F, 0x2ECC71, 0xE74C3C, 0x3498DB, true, true, true, true);
    }

    private static BountyConfig limitedConfig() {
        return new BountyConfig(100, 1_000, 80, 3600, 28, false, false, true, "https://example.test/webhook", "Bounty", "", "Bounty", true, 0xF1C40F, 0x2ECC71, 0xE74C3C, 0x3498DB, true, true, true, true);
    }

    private static BountyConfig disabledDiscordConfig() {
        return new BountyConfig(100, 0, 80, 3600, 28, false, false, false, "", "Bounty", "", "Bounty", true, 0xF1C40F, 0x2ECC71, 0xE74C3C, 0x3498DB, true, true, true, true);
    }

    private static BountyConfig invalidDiscordConfig() {
        return new BountyConfig(100, 0, 80, 3600, 28, false, false, true, "://bad-url", "Bounty", "", "Bounty", true, 0xF1C40F, 0x2ECC71, 0xE74C3C, 0x3498DB, true, true, true, true);
    }

    private static BountyConfig relativeDiscordConfig() {
        return new BountyConfig(100, 0, 80, 3600, 28, false, false, true, "discord/webhook", "Bounty", "", "Bounty", true, 0xF1C40F, 0x2ECC71, 0xE74C3C, 0x3498DB, true, true, true, true);
    }

    private static final class FakeNotifier implements BountyNotifier {
        private int placedEvents;
        private int cancelEvents;
        private int claimEvents;
        private int testEvents;

        @Override
        public void notifyBountyPlaced(String placerName, String targetName, long amount, long totalPool, boolean adminAction) {
            placedEvents++;
        }

        @Override
        public void notifyBountyCancelled(String placerName, String targetName, long refundAmount) {
            cancelEvents++;
        }

        @Override
        public void notifyBountyClaimed(String killerName, String targetName, long totalAmount, int sourceCount) {
            claimEvents++;
        }

        @Override
        public void notifyAdminTargetRemoved(String targetName, int removedContributions) {
        }

        @Override
        public void notifyAdminRefund(String targetName, long refundedAmount, int refundedContributions) {
        }

        @Override
        public void notifyTestMessage(String requestedBy) {
            testEvents++;
        }
    }
    private static final class FakeEconomy implements EconomyAdapter {
        private final Map<UUID, Double> balances = new HashMap<>();
        private final Map<UUID, Integer> failingDeposits = new HashMap<>();

        void setBalance(UUID uuid, double amount) {
            balances.put(uuid, amount);
        }

        void failNextDepositFor(UUID uuid) {
            failingDeposits.put(uuid, failingDeposits.getOrDefault(uuid, 0) + 1);
        }

        double balance(UUID uuid) {
            return balances.getOrDefault(uuid, 0.0D);
        }

        @Override
        public boolean has(UUID playerId, double amount) {
            return balance(playerId) >= amount;
        }

        @Override
        public boolean withdraw(UUID playerId, String playerName, double amount) {
            if (!has(playerId, amount)) {
                return false;
            }
            balances.put(playerId, balance(playerId) - amount);
            return true;
        }

        @Override
        public boolean deposit(UUID playerId, String playerName, double amount) {
            int remainingFailures = failingDeposits.getOrDefault(playerId, 0);
            if (remainingFailures > 0) {
                failingDeposits.put(playerId, remainingFailures - 1);
                return false;
            }
            balances.put(playerId, balance(playerId) + amount);
            return true;
        }
    }

    private static final class InMemoryRepository implements BountyRepository {
        private final Map<Long, BountyContribution> contributions = new HashMap<>();
        private final Map<String, Instant> abuseLocks = new HashMap<>();
        private final List<BountyClaim> claims = new ArrayList<>();
        private boolean failOnRecordClaim;
        private boolean failNextTransition;
        private boolean failFinalizeClaim;
        private long nextId = 1;
        private long nextClaimId = 1;

        @Override
        public void upsertActiveContribution(UUID targetUuid, String targetName, UUID placerUuid, String placerName, long amount, boolean adminFunded) {
            Optional<BountyContribution> current = contributions.values().stream()
                .filter(value -> value.targetUuid().equals(targetUuid))
                .filter(value -> value.placerUuid().equals(placerUuid))
                .filter(value -> value.adminFunded() == adminFunded)
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .findFirst();
            Instant now = Instant.now();
            if (current.isPresent()) {
                BountyContribution existing = current.get();
                contributions.put(existing.id(), new BountyContribution(
                    existing.id(), targetUuid, targetName, placerUuid, placerName,
                    existing.amount() + amount, adminFunded, ContributionStatus.ACTIVE, existing.createdAt(), now
                ));
                return;
            }
            contributions.put(nextId, new BountyContribution(
                nextId++, targetUuid, targetName, placerUuid, placerName, amount,
                adminFunded, ContributionStatus.ACTIVE, now, now
            ));
        }

        @Override
        public Optional<BountyContribution> getActiveContribution(UUID targetUuid, UUID placerUuid) {
            return contributions.values().stream()
                .filter(value -> value.targetUuid().equals(targetUuid))
                .filter(value -> value.placerUuid().equals(placerUuid))
                .filter(value -> !value.adminFunded())
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .findFirst();
        }

        @Override
        public List<BountyContribution> getActiveContributionsByTarget(UUID targetUuid) {
            return contributions.values().stream()
                .filter(value -> value.targetUuid().equals(targetUuid))
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .sorted(Comparator.comparing(BountyContribution::updatedAt).reversed())
                .toList();
        }

        @Override
        public List<BountyContribution> getActiveContributionsByPlacer(UUID placerUuid) {
            return contributions.values().stream()
                .filter(value -> value.placerUuid().equals(placerUuid))
                .filter(value -> !value.adminFunded())
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .toList();
        }

        @Override
        public long getActiveTotalForTarget(UUID targetUuid) {
            return getActiveContributionsByTarget(targetUuid).stream().mapToLong(BountyContribution::amount).sum();
        }

        @Override
        public Optional<BountyTargetSummary> getTargetSummary(UUID targetUuid) {
            List<BountyContribution> byTarget = getActiveContributionsByTarget(targetUuid);
            if (byTarget.isEmpty()) {
                return Optional.empty();
            }
            int contributors = (int) byTarget.stream()
                .map(BountyContribution::placerUuid)
                .distinct()
                .count();
            return Optional.of(new BountyTargetSummary(targetUuid, byTarget.getFirst().targetName(), getActiveTotalForTarget(targetUuid), contributors));
        }

        @Override
        public int countActiveTargets() {
            return (int) contributions.values().stream()
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .map(BountyContribution::targetUuid)
                .distinct()
                .count();
        }

        @Override
        public List<BountyTargetSummary> listActiveTargetSummaries(int limit, int offset) {
            return contributions.values().stream()
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .collect(java.util.stream.Collectors.groupingBy(BountyContribution::targetUuid))
                .values().stream()
                .map(list -> {
                    List<BountyContribution> sorted = list.stream()
                        .sorted(Comparator.comparing(BountyContribution::updatedAt).reversed())
                        .toList();
                    return new BountyTargetSummary(
                        sorted.getFirst().targetUuid(),
                        sorted.getFirst().targetName(),
                        sorted.stream().mapToLong(BountyContribution::amount).sum(),
                        (int) sorted.stream().map(BountyContribution::placerUuid).distinct().count()
                    );
                })
                .sorted(Comparator.comparingLong(BountyTargetSummary::totalAmount).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
        }

        @Override
        public List<BountyTargetSummary> listTopTargetSummaries(int limit) {
            return listActiveTargetSummaries(limit, 0);
        }

        @Override
        public void updateContributionStatus(long id, ContributionStatus status) {
            BountyContribution existing = contributions.get(id);
            contributions.put(id, new BountyContribution(
                existing.id(), existing.targetUuid(), existing.targetName(), existing.placerUuid(), existing.placerName(),
                existing.amount(), existing.adminFunded(), status, existing.createdAt(), Instant.now()
            ));
        }

        @Override
        public boolean transitionContributionStatus(long id, ContributionStatus fromStatus, ContributionStatus toStatus) {
            if (failNextTransition) {
                failNextTransition = false;
                return false;
            }
            BountyContribution existing = contributions.get(id);
            if (existing == null || existing.status() != fromStatus) {
                return false;
            }
            updateContributionStatus(id, toStatus);
            return true;
        }

        @Override
        public int updateTargetContributionsStatus(UUID targetUuid, ContributionStatus status) {
            int updated = 0;
            for (BountyContribution contribution : new ArrayList<>(contributions.values())) {
                if (contribution.targetUuid().equals(targetUuid) && contribution.status() == ContributionStatus.ACTIVE) {
                    updateContributionStatus(contribution.id(), status);
                    updated++;
                }
            }
            return updated;
        }

        @Override
        public int finalizeClaim(
            List<Long> contributionIds,
            UUID targetUuid,
            String targetName,
            UUID killerUuid,
            String killerName,
            long totalAmount,
            int sourceCount,
            Instant claimedAt
        ) throws SQLException {
            if (failFinalizeClaim) {
                return 0;
            }
            int updated = 0;
            List<Long> transitioned = new ArrayList<>();
            for (Long contributionId : contributionIds) {
                if (!transitionContributionStatus(contributionId, ContributionStatus.ACTIVE, ContributionStatus.CLAIMED)) {
                    for (Long transitionedId : transitioned) {
                        transitionContributionStatus(transitionedId, ContributionStatus.CLAIMED, ContributionStatus.ACTIVE);
                    }
                    return 0;
                }
                transitioned.add(contributionId);
                updated++;
            }
            try {
                recordClaim(targetUuid, targetName, killerUuid, killerName, totalAmount, sourceCount);
                upsertAbuseLock(killerUuid, targetUuid, claimedAt);
            } catch (SQLException exception) {
                for (Long transitionedId : transitioned) {
                    transitionContributionStatus(transitionedId, ContributionStatus.CLAIMED, ContributionStatus.ACTIVE);
                }
                throw exception;
            }
            return updated;
        }

        @Override
        public void recordClaim(UUID targetUuid, String targetName, UUID killerUuid, String killerName, long totalAmount, int sourceCount)
            throws SQLException {
            if (failOnRecordClaim) {
                throw new SQLException("forced record claim failure");
            }
            claims.add(new BountyClaim(nextClaimId++, targetUuid, targetName, killerUuid, killerName, totalAmount, sourceCount, Instant.now()));
        }

        @Override
        public List<BountyClaim> getClaimHistory(UUID targetUuid, int limit) {
            return claims.stream().filter(claim -> claim.targetUuid().equals(targetUuid)).limit(limit).toList();
        }

        @Override
        public void upsertAbuseLock(UUID killerUuid, UUID targetUuid, Instant claimedAt) {
            abuseLocks.put(killerUuid + ":" + targetUuid, claimedAt);
        }

        @Override
        public Optional<Instant> getLastClaimForPair(UUID killerUuid, UUID targetUuid) {
            return Optional.ofNullable(abuseLocks.get(killerUuid + ":" + targetUuid));
        }

        @Override
        public void close() {
        }

        long getUnsafeTotal(UUID targetUuid) {
            return getActiveTotalForTarget(targetUuid);
        }

        List<BountyContribution> getUnsafeByTarget(UUID targetUuid) {
            return getActiveContributionsByTarget(targetUuid);
        }

        List<BountyContribution> getUnsafeByPlacer(UUID placerUuid) {
            return contributions.values().stream()
                .filter(value -> value.placerUuid().equals(placerUuid))
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .toList();
        }
    }
}
