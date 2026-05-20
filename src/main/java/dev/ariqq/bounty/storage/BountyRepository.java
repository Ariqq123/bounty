package dev.ariqq.bounty.storage;

import dev.ariqq.bounty.model.BountyClaim;
import dev.ariqq.bounty.model.BountyContribution;
import dev.ariqq.bounty.model.BountyTargetSummary;
import dev.ariqq.bounty.model.ContributionStatus;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BountyRepository extends AutoCloseable {
    void upsertActiveContribution(UUID targetUuid, String targetName, UUID placerUuid, String placerName, long amount) throws SQLException;

    Optional<BountyContribution> getActiveContribution(UUID targetUuid, UUID placerUuid) throws SQLException;

    List<BountyContribution> getActiveContributionsByTarget(UUID targetUuid) throws SQLException;

    List<BountyContribution> getActiveContributionsByPlacer(UUID placerUuid) throws SQLException;

    long getActiveTotalForTarget(UUID targetUuid) throws SQLException;

    Optional<BountyTargetSummary> getTargetSummary(UUID targetUuid) throws SQLException;

    int countActiveTargets() throws SQLException;

    List<BountyTargetSummary> listActiveTargetSummaries(int limit, int offset) throws SQLException;

    List<BountyTargetSummary> listTopTargetSummaries(int limit) throws SQLException;

    void updateContributionStatus(long id, ContributionStatus status) throws SQLException;

    int updateTargetContributionsStatus(UUID targetUuid, ContributionStatus status) throws SQLException;

    void recordClaim(UUID targetUuid, String targetName, UUID killerUuid, String killerName, long totalAmount, int sourceCount) throws SQLException;

    List<BountyClaim> getClaimHistory(UUID targetUuid, int limit) throws SQLException;

    void upsertAbuseLock(UUID killerUuid, UUID targetUuid, Instant claimedAt) throws SQLException;

    Optional<Instant> getLastClaimForPair(UUID killerUuid, UUID targetUuid) throws SQLException;

    @Override
    void close();
}
