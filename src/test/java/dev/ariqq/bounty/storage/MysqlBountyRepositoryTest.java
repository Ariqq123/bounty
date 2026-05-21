package dev.ariqq.bounty.storage;

import dev.ariqq.bounty.model.BountyContribution;
import dev.ariqq.bounty.model.BountyTargetSummary;
import dev.ariqq.bounty.model.ContributionStatus;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Tag("integration")
class MysqlBountyRepositoryTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("bounty_test")
        .withUsername("test")
        .withPassword("test");

    private static MysqlBountyRepository repository;

    @BeforeAll
    static void setUp() throws SQLException {
        repository = new MysqlBountyRepository(
            MYSQL.getHost(),
            MYSQL.getMappedPort(3306),
            "bounty_test",
            "test",
            "test",
            false
        );
    }

    @AfterAll
    static void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    void upsertMergesAmountAtomically() throws SQLException {
        UUID target = UUID.randomUUID();
        UUID placer = UUID.randomUUID();

        repository.upsertActiveContribution(target, "Target", placer, "Placer", 100, false);
        repository.upsertActiveContribution(target, "Target", placer, "Placer", 200, false);

        long total = repository.getActiveTotalForTarget(target);
        Assertions.assertEquals(300L, total);

        List<BountyContribution> contributions = repository.getActiveContributionsByTarget(target);
        Assertions.assertEquals(1, contributions.size());
        Assertions.assertEquals(300L, contributions.get(0).amount());
    }

    @Test
    void upsertKeepsSeparateRowsForDifferentPlacers() throws SQLException {
        UUID target = UUID.randomUUID();
        UUID placer1 = UUID.randomUUID();
        UUID placer2 = UUID.randomUUID();

        repository.upsertActiveContribution(target, "Target", placer1, "Placer1", 100, false);
        repository.upsertActiveContribution(target, "Target", placer2, "Placer2", 200, false);

        long total = repository.getActiveTotalForTarget(target);
        Assertions.assertEquals(300L, total);

        List<BountyContribution> contributions = repository.getActiveContributionsByTarget(target);
        Assertions.assertEquals(2, contributions.size());
    }

    @Test
    void upsertKeepsSeparateRowsForAdminVsPlayer() throws SQLException {
        UUID target = UUID.randomUUID();
        UUID placer = UUID.randomUUID();

        repository.upsertActiveContribution(target, "Target", placer, "Placer", 100, false);
        repository.upsertActiveContribution(target, "Target", placer, "Placer", 200, true);

        List<BountyContribution> contributions = repository.getActiveContributionsByTarget(target);
        Assertions.assertEquals(2, contributions.size());

        long total = repository.getActiveTotalForTarget(target);
        Assertions.assertEquals(300L, total);
    }

    @Test
    void concurrentUpsertProducesCorrectTotal() throws Exception {
        UUID target = UUID.randomUUID();
        UUID placer = UUID.randomUUID();
        int threadCount = 20;
        long amountPerThread = 50L;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    repository.upsertActiveContribution(target, "Target", placer, "Placer", amountPerThread, false);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        Assertions.assertEquals(0, errors.get(), "No threads should have failed");

        long total = repository.getActiveTotalForTarget(target);
        Assertions.assertEquals(threadCount * amountPerThread, total,
            "All concurrent upserts should merge into correct total");

        List<BountyContribution> contributions = repository.getActiveContributionsByTarget(target);
        Assertions.assertEquals(1, contributions.size(),
            "All upserts from same placer should merge into one row");
    }

    @Test
    void finalizeClaimIsAtomic() throws Exception {
        UUID target = UUID.randomUUID();
        UUID placer1 = UUID.randomUUID();
        UUID placer2 = UUID.randomUUID();
        UUID killer = UUID.randomUUID();

        repository.upsertActiveContribution(target, "Target", placer1, "P1", 500, false);
        repository.upsertActiveContribution(target, "Target", placer2, "P2", 300, false);

        List<BountyContribution> contributions = repository.getActiveContributionsByTarget(target);
        List<Long> ids = contributions.stream().map(BountyContribution::id).toList();

        int claimed = repository.finalizeClaim(ids, target, "Target", killer, "Killer", 800, 2, Instant.now());

        Assertions.assertEquals(2, claimed);
        Assertions.assertEquals(0L, repository.getActiveTotalForTarget(target));

        var claims = repository.getClaimHistory(target, 10);
        Assertions.assertEquals(1, claims.size());
        Assertions.assertEquals(800L, claims.get(0).totalAmount());
    }

    @Test
    void transitionStatusRollsBackOnMismatch() throws SQLException {
        UUID target = UUID.randomUUID();
        UUID placer = UUID.randomUUID();

        repository.upsertActiveContribution(target, "Target", placer, "Placer", 100, false);

        List<BountyContribution> contributions = repository.getActiveContributionsByTarget(target);
        long realId = contributions.get(0).id();

        // Try to transition with a fake extra ID that doesn't exist
        int result = repository.transitionContributionStatuses(
            List.of(realId, 999999L),
            ContributionStatus.ACTIVE,
            ContributionStatus.CLAIMED
        );

        // Should rollback — mismatch between expected and actual updated count
        Assertions.assertEquals(0, result);

        // Original contribution should still be ACTIVE
        Assertions.assertEquals(100L, repository.getActiveTotalForTarget(target));
    }
}
