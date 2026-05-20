package dev.ariqq.bounty.service;

import dev.ariqq.bounty.config.BountyConfig;
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
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        ServiceResult first = service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 250);
        ServiceResult second = service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 500);

        Assertions.assertTrue(first.success());
        Assertions.assertTrue(second.success());
        Assertions.assertEquals(750L, repository.getUnsafeTotal(target));
        Assertions.assertEquals(1, repository.getUnsafeByTarget(target).size());
    }

    @Test
    void cancelReturnsConfiguredRefund() {
        InMemoryRepository repository = new InMemoryRepository();
        FakeEconomy economy = new FakeEconomy();
        BountyService service = new BountyService(null, Logger.getLogger("test"), repository, economy, BountyServiceTest::testConfig);
        UUID placer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        economy.setBalance(placer, 5_000);
        service.placeBounty(placer, "Hunter", new KnownPlayer(target, "Target"), 1000);
        ServiceResult result = service.cancelOwnBounty(placer, "Hunter", new KnownPlayer(target, "Target"));

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(4_800D, economy.balance(placer));
    }

    private static BountyConfig testConfig() {
        return new BountyConfig(100, 0, 80, 3600, 28, false, false);
    }
    private static final class FakeEconomy implements EconomyAdapter {
        private final Map<UUID, Double> balances = new HashMap<>();

        void setBalance(UUID uuid, double amount) {
            balances.put(uuid, amount);
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
            balances.put(playerId, balance(playerId) + amount);
            return true;
        }
    }

    private static final class InMemoryRepository implements BountyRepository {
        private final Map<Long, BountyContribution> contributions = new HashMap<>();
        private final Map<String, Instant> abuseLocks = new HashMap<>();
        private final List<BountyClaim> claims = new ArrayList<>();
        private long nextId = 1;
        private long nextClaimId = 1;

        @Override
        public void upsertActiveContribution(UUID targetUuid, String targetName, UUID placerUuid, String placerName, long amount) {
            Optional<BountyContribution> current = getActiveContribution(targetUuid, placerUuid);
            Instant now = Instant.now();
            if (current.isPresent()) {
                BountyContribution existing = current.get();
                contributions.put(existing.id(), new BountyContribution(
                    existing.id(), targetUuid, targetName, placerUuid, placerName,
                    existing.amount() + amount, ContributionStatus.ACTIVE, existing.createdAt(), now
                ));
                return;
            }
            contributions.put(nextId, new BountyContribution(
                nextId++, targetUuid, targetName, placerUuid, placerName, amount,
                ContributionStatus.ACTIVE, now, now
            ));
        }

        @Override
        public Optional<BountyContribution> getActiveContribution(UUID targetUuid, UUID placerUuid) {
            return contributions.values().stream()
                .filter(value -> value.targetUuid().equals(targetUuid))
                .filter(value -> value.placerUuid().equals(placerUuid))
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
            return Optional.of(new BountyTargetSummary(targetUuid, byTarget.getFirst().targetName(), getActiveTotalForTarget(targetUuid), byTarget.size()));
        }

        @Override
        public List<BountyTargetSummary> listActiveTargetSummaries(int limit, int offset) {
            return contributions.values().stream()
                .filter(value -> value.status() == ContributionStatus.ACTIVE)
                .collect(java.util.stream.Collectors.groupingBy(BountyContribution::targetUuid))
                .values().stream()
                .map(list -> new BountyTargetSummary(
                    list.getFirst().targetUuid(),
                    list.getFirst().targetName(),
                    list.stream().mapToLong(BountyContribution::amount).sum(),
                    list.size()
                ))
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
                existing.amount(), status, existing.createdAt(), Instant.now()
            ));
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
        public void recordClaim(UUID targetUuid, String targetName, UUID killerUuid, String killerName, long totalAmount, int sourceCount) {
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
    }
}
