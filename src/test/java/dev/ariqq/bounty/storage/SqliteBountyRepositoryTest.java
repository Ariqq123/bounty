package dev.ariqq.bounty.storage;

import dev.ariqq.bounty.model.BountyClaim;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBountyRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void constructorSupportsPathWithoutParent() throws Exception {
        Path databasePath = Path.of("sqlite-bounty-no-parent-test.db");
        Files.deleteIfExists(databasePath);
        try (SqliteBountyRepository repository = new SqliteBountyRepository(databasePath)) {
            repository.upsertActiveContribution(
                UUID.randomUUID(),
                "Target",
                UUID.randomUUID(),
                "Hunter",
                250L,
                false
            );
            Assertions.assertEquals(1, repository.countActiveTargets());
        } finally {
            Files.deleteIfExists(databasePath);
        }
    }

    @Test
    void finalizeClaimPersistsClaimAndClearsActivePool() throws Exception {
        UUID targetUuid = UUID.randomUUID();
        UUID killerUuid = UUID.randomUUID();
        UUID placerOne = UUID.randomUUID();
        UUID placerTwo = UUID.randomUUID();
        Instant claimedAt = Instant.parse("2026-05-20T00:00:00Z");

        try (SqliteBountyRepository repository = new SqliteBountyRepository(tempDir.resolve("bounty.db"))) {
            repository.upsertActiveContribution(targetUuid, "Target", placerOne, "HunterOne", 300L, false);
            repository.upsertActiveContribution(targetUuid, "Target", placerTwo, "HunterTwo", 500L, false);

            List<Long> contributionIds = repository.getActiveContributionsByTarget(targetUuid).stream()
                .map(contribution -> contribution.id())
                .toList();

            int finalized = repository.finalizeClaim(
                contributionIds,
                targetUuid,
                "Target",
                killerUuid,
                "Slayer",
                800L,
                2,
                claimedAt
            );

            Assertions.assertEquals(2, finalized);
            Assertions.assertEquals(0L, repository.getActiveTotalForTarget(targetUuid));
            List<BountyClaim> claims = repository.getClaimHistory(targetUuid, 10);
            Assertions.assertEquals(1, claims.size());
            Assertions.assertEquals("Slayer", claims.getFirst().killerName());
            Assertions.assertEquals(800L, claims.getFirst().totalAmount());
            Assertions.assertEquals(claimedAt, repository.getLastClaimForPair(killerUuid, targetUuid).orElseThrow());
        }
    }

    @Test
    void targetSummaryCountsUniqueContributorsAcrossFundingSources() throws Exception {
        UUID targetUuid = UUID.randomUUID();
        UUID placerUuid = UUID.randomUUID();

        try (SqliteBountyRepository repository = new SqliteBountyRepository(tempDir.resolve("bounty.db"))) {
            repository.upsertActiveContribution(targetUuid, "Target", placerUuid, "Hunter", 300L, false);
            repository.upsertActiveContribution(targetUuid, "Target", placerUuid, "Hunter", 700L, true);

            var summary = repository.getTargetSummary(targetUuid).orElseThrow();
            Assertions.assertEquals(1_000L, summary.totalAmount());
            Assertions.assertEquals(1, summary.contributorCount());

            var listed = repository.listActiveTargetSummaries(10, 0);
            Assertions.assertEquals(1, listed.size());
            Assertions.assertEquals(1, listed.getFirst().contributorCount());
        }
    }
}
