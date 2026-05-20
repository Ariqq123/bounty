package dev.ariqq.bounty.storage;

import dev.ariqq.bounty.model.BountyClaim;
import dev.ariqq.bounty.model.BountyContribution;
import dev.ariqq.bounty.model.BountyTargetSummary;
import dev.ariqq.bounty.model.ContributionStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqliteBountyRepository implements BountyRepository {
    private final Connection connection;

    public SqliteBountyRepository(Path databasePath) throws SQLException {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (java.io.IOException exception) {
            throw new SQLException("Could not create plugin data folder", exception);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA cache_size = -16000");
            statement.execute("PRAGMA temp_store = MEMORY");

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bounty_contributions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    target_uuid TEXT NOT NULL,
                    target_name TEXT NOT NULL,
                    placer_uuid TEXT NOT NULL,
                    placer_name TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    admin_funded INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
            ensureAdminFundedColumn(statement);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bounty_claims (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    target_uuid TEXT NOT NULL,
                    target_name TEXT NOT NULL,
                    killer_uuid TEXT NOT NULL,
                    killer_name TEXT NOT NULL,
                    total_amount INTEGER NOT NULL,
                    source_count INTEGER NOT NULL,
                    claimed_at TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS abuse_claim_locks (
                    killer_uuid TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    claimed_at TEXT NOT NULL,
                    PRIMARY KEY (killer_uuid, target_uuid)
                )
                """);
            
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bounty_contributions_target_status ON bounty_contributions(target_uuid, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bounty_contributions_placer_status ON bounty_contributions(placer_uuid, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bounty_contributions_status ON bounty_contributions(status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bounty_claims_target ON bounty_claims(target_uuid)");
        }
    }

    @Override
    public synchronized void upsertActiveContribution(UUID targetUuid, String targetName, UUID placerUuid, String placerName, long amount, boolean adminFunded)
        throws SQLException {
        Instant now = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE bounty_contributions
            SET amount = amount + ?, target_name = ?, placer_name = ?, updated_at = ?
            WHERE target_uuid = ? AND placer_uuid = ? AND admin_funded = ? AND status = ?
            """)) {
            statement.setLong(1, amount);
            statement.setString(2, targetName);
            statement.setString(3, placerName);
            statement.setString(4, now.toString());
            statement.setString(5, targetUuid.toString());
            statement.setString(6, placerUuid.toString());
            statement.setInt(7, adminFunded ? 1 : 0);
            statement.setString(8, ContributionStatus.ACTIVE.name());
            if (statement.executeUpdate() > 0) {
                return;
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO bounty_contributions
            (target_uuid, target_name, placer_uuid, placer_name, amount, admin_funded, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, targetName);
            statement.setString(3, placerUuid.toString());
            statement.setString(4, placerName);
            statement.setLong(5, amount);
            statement.setInt(6, adminFunded ? 1 : 0);
            statement.setString(7, ContributionStatus.ACTIVE.name());
            statement.setString(8, now.toString());
            statement.setString(9, now.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized Optional<BountyContribution> getActiveContribution(UUID targetUuid, UUID placerUuid) throws SQLException {
        return getActiveContributionInternal(targetUuid, placerUuid, false);
    }

    private Optional<BountyContribution> getActiveContributionInternal(UUID targetUuid, UUID placerUuid, boolean adminFunded) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM bounty_contributions
            WHERE target_uuid = ? AND placer_uuid = ? AND admin_funded = ? AND status = ?
            LIMIT 1
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, placerUuid.toString());
            statement.setInt(3, adminFunded ? 1 : 0);
            statement.setString(4, ContributionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapContribution(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized List<BountyContribution> getActiveContributionsByTarget(UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM bounty_contributions
            WHERE target_uuid = ? AND status = ?
            ORDER BY updated_at DESC
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, ContributionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapContributions(resultSet);
            }
        }
    }

    @Override
    public synchronized List<BountyContribution> getActiveContributionsByPlacer(UUID placerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM bounty_contributions
            WHERE placer_uuid = ? AND admin_funded = 0 AND status = ?
            ORDER BY updated_at DESC
            """)) {
            statement.setString(1, placerUuid.toString());
            statement.setString(2, ContributionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapContributions(resultSet);
            }
        }
    }

    @Override
    public synchronized long getActiveTotalForTarget(UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COALESCE(SUM(amount), 0) AS total
            FROM bounty_contributions
            WHERE target_uuid = ? AND status = ?
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, ContributionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.getLong("total");
            }
        }
    }

    @Override
    public synchronized Optional<BountyTargetSummary> getTargetSummary(UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
                summary.target_uuid,
                (
                    SELECT latest.target_name
                    FROM bounty_contributions latest
                    WHERE latest.target_uuid = summary.target_uuid AND latest.status = ?
                    ORDER BY latest.updated_at DESC, latest.id DESC
                    LIMIT 1
                ) AS target_name,
                summary.total,
                summary.contributors
            FROM (
                SELECT target_uuid, COALESCE(SUM(amount), 0) AS total, COUNT(DISTINCT placer_uuid) AS contributors
                FROM bounty_contributions
                WHERE target_uuid = ? AND status = ?
                GROUP BY target_uuid
            ) summary
            """)) {
            statement.setString(1, ContributionStatus.ACTIVE.name());
            statement.setString(2, targetUuid.toString());
            statement.setString(3, ContributionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapTargetSummary(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized int countActiveTargets() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(*) AS total
            FROM (
                SELECT target_uuid
                FROM bounty_contributions
                WHERE status = ?
                GROUP BY target_uuid
            ) grouped_targets
            """)) {
            statement.setString(1, ContributionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.getInt("total");
            }
        }
    }

    @Override
    public synchronized List<BountyTargetSummary> listActiveTargetSummaries(int limit, int offset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT
                summary.target_uuid,
                (
                    SELECT latest.target_name
                    FROM bounty_contributions latest
                    WHERE latest.target_uuid = summary.target_uuid AND latest.status = ?
                    ORDER BY latest.updated_at DESC, latest.id DESC
                    LIMIT 1
                ) AS target_name,
                summary.total,
                summary.contributors
            FROM (
                SELECT target_uuid, COALESCE(SUM(amount), 0) AS total, COUNT(DISTINCT placer_uuid) AS contributors
                FROM bounty_contributions
                WHERE status = ?
                GROUP BY target_uuid
            ) summary
            ORDER BY summary.total DESC, target_name ASC
            LIMIT ? OFFSET ?
            """)) {
            statement.setString(1, ContributionStatus.ACTIVE.name());
            statement.setString(2, ContributionStatus.ACTIVE.name());
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapTargetSummaries(resultSet);
            }
        }
    }

    @Override
    public synchronized List<BountyTargetSummary> listTopTargetSummaries(int limit) throws SQLException {
        return listActiveTargetSummaries(limit, 0);
    }

    @Override
    public synchronized void updateContributionStatus(long id, ContributionStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE bounty_contributions
            SET status = ?, updated_at = ?
            WHERE id = ?
            """)) {
            statement.setString(1, status.name());
            statement.setString(2, Instant.now().toString());
            statement.setLong(3, id);
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized boolean transitionContributionStatus(long id, ContributionStatus fromStatus, ContributionStatus toStatus) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE bounty_contributions
            SET status = ?, updated_at = ?
            WHERE id = ? AND status = ?
            """)) {
            statement.setString(1, toStatus.name());
            statement.setString(2, Instant.now().toString());
            statement.setLong(3, id);
            statement.setString(4, fromStatus.name());
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public synchronized int transitionContributionStatuses(List<Long> contributionIds, ContributionStatus fromStatus, ContributionStatus toStatus) throws SQLException {
        if (contributionIds.isEmpty()) {
            return 0;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int updated = transitionContributionStatusesInternal(contributionIds, fromStatus, toStatus, Instant.now());
            if (updated != contributionIds.size()) {
                connection.rollback();
                return 0;
            }
            connection.commit();
            return updated;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @Override
    public synchronized int updateTargetContributionsStatus(UUID targetUuid, ContributionStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            UPDATE bounty_contributions
            SET status = ?, updated_at = ?
            WHERE target_uuid = ? AND status = ?
            """)) {
            statement.setString(1, status.name());
            statement.setString(2, Instant.now().toString());
            statement.setString(3, targetUuid.toString());
            statement.setString(4, ContributionStatus.ACTIVE.name());
            return statement.executeUpdate();
        }
    }

    @Override
    public synchronized int finalizeClaim(
        List<Long> contributionIds,
        UUID targetUuid,
        String targetName,
        UUID killerUuid,
        String killerName,
        long totalAmount,
        int sourceCount,
        Instant claimedAt
    ) throws SQLException {
        if (contributionIds.isEmpty()) {
            return 0;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int updated = transitionContributionStatusesInternal(contributionIds, ContributionStatus.ACTIVE, ContributionStatus.CLAIMED, claimedAt);
            if (updated != contributionIds.size()) {
                connection.rollback();
                return 0;
            }

            insertClaimInternal(targetUuid, targetName, killerUuid, killerName, totalAmount, sourceCount, claimedAt);
            upsertAbuseLockInternal(killerUuid, targetUuid, claimedAt);
            connection.commit();
            return updated;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @Override
    public synchronized void recordClaim(UUID targetUuid, String targetName, UUID killerUuid, String killerName, long totalAmount, int sourceCount)
        throws SQLException {
        insertClaimInternal(targetUuid, targetName, killerUuid, killerName, totalAmount, sourceCount, Instant.now());
    }

    @Override
    public synchronized List<BountyClaim> getClaimHistory(UUID targetUuid, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM bounty_claims
            WHERE target_uuid = ?
            ORDER BY claimed_at DESC
            LIMIT ?
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<BountyClaim> claims = new ArrayList<>();
                while (resultSet.next()) {
                    claims.add(new BountyClaim(
                        resultSet.getLong("id"),
                        UUID.fromString(resultSet.getString("target_uuid")),
                        resultSet.getString("target_name"),
                        UUID.fromString(resultSet.getString("killer_uuid")),
                        resultSet.getString("killer_name"),
                        resultSet.getLong("total_amount"),
                        resultSet.getInt("source_count"),
                        Instant.parse(resultSet.getString("claimed_at"))
                    ));
                }
                return claims;
            }
        }
    }

    @Override
    public synchronized void upsertAbuseLock(UUID killerUuid, UUID targetUuid, Instant claimedAt) throws SQLException {
        upsertAbuseLockInternal(killerUuid, targetUuid, claimedAt);
    }

    @Override
    public synchronized Optional<Instant> getLastClaimForPair(UUID killerUuid, UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT claimed_at FROM abuse_claim_locks
            WHERE killer_uuid = ? AND target_uuid = ?
            LIMIT 1
            """)) {
            statement.setString(1, killerUuid.toString());
            statement.setString(2, targetUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(Instant.parse(resultSet.getString("claimed_at"))) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private List<BountyContribution> mapContributions(ResultSet resultSet) throws SQLException {
        List<BountyContribution> contributions = new ArrayList<>();
        while (resultSet.next()) {
            contributions.add(mapContribution(resultSet));
        }
        return contributions;
    }

    private int transitionContributionStatusesInternal(
        List<Long> contributionIds,
        ContributionStatus fromStatus,
        ContributionStatus toStatus,
        Instant updatedAt
    ) throws SQLException {
        if (contributionIds.isEmpty()) {
            return 0;
        }

        String placeholders = String.join(",", Collections.nCopies(contributionIds.size(), "?"));
        String sql = "UPDATE bounty_contributions "
            + "SET status = ?, updated_at = ? "
            + "WHERE id IN (" + placeholders + ") AND status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toStatus.name());
            statement.setString(2, updatedAt.toString());

            int parameterIndex = 3;
            for (Long contributionId : contributionIds) {
                statement.setLong(parameterIndex++, contributionId);
            }
            statement.setString(parameterIndex, fromStatus.name());
            return statement.executeUpdate();
        }
    }

    private void insertClaimInternal(
        UUID targetUuid,
        String targetName,
        UUID killerUuid,
        String killerName,
        long totalAmount,
        int sourceCount,
        Instant claimedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO bounty_claims
            (target_uuid, target_name, killer_uuid, killer_name, total_amount, source_count, claimed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, targetName);
            statement.setString(3, killerUuid.toString());
            statement.setString(4, killerName);
            statement.setLong(5, totalAmount);
            statement.setInt(6, sourceCount);
            statement.setString(7, claimedAt.toString());
            statement.executeUpdate();
        }
    }

    private void upsertAbuseLockInternal(UUID killerUuid, UUID targetUuid, Instant claimedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO abuse_claim_locks (killer_uuid, target_uuid, claimed_at)
            VALUES (?, ?, ?)
            ON CONFLICT(killer_uuid, target_uuid) DO UPDATE SET claimed_at = excluded.claimed_at
            """)) {
            statement.setString(1, killerUuid.toString());
            statement.setString(2, targetUuid.toString());
            statement.setString(3, claimedAt.toString());
            statement.executeUpdate();
        }
    }

    private BountyContribution mapContribution(ResultSet resultSet) throws SQLException {
        return new BountyContribution(
            resultSet.getLong("id"),
            UUID.fromString(resultSet.getString("target_uuid")),
            resultSet.getString("target_name"),
            UUID.fromString(resultSet.getString("placer_uuid")),
            resultSet.getString("placer_name"),
            resultSet.getLong("amount"),
            resultSet.getInt("admin_funded") != 0,
            ContributionStatus.valueOf(resultSet.getString("status")),
            Instant.parse(resultSet.getString("created_at")),
            Instant.parse(resultSet.getString("updated_at"))
        );
    }

    private void ensureAdminFundedColumn(Statement statement) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE bounty_contributions ADD COLUMN admin_funded INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column name")) {
                throw exception;
            }
        }
    }

    private List<BountyTargetSummary> mapTargetSummaries(ResultSet resultSet) throws SQLException {
        List<BountyTargetSummary> summaries = new ArrayList<>();
        while (resultSet.next()) {
            summaries.add(mapTargetSummary(resultSet));
        }
        return summaries;
    }

    private BountyTargetSummary mapTargetSummary(ResultSet resultSet) throws SQLException {
        return new BountyTargetSummary(
            UUID.fromString(resultSet.getString("target_uuid")),
            resultSet.getString("target_name"),
            resultSet.getLong("total"),
            resultSet.getInt("contributors")
        );
    }
}
