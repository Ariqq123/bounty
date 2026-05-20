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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqliteBountyRepository implements BountyRepository {
    private final Connection connection;

    public SqliteBountyRepository(Path databasePath) throws SQLException {
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (java.io.IOException exception) {
            throw new SQLException("Could not create plugin data folder", exception);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bounty_contributions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    target_uuid TEXT NOT NULL,
                    target_name TEXT NOT NULL,
                    placer_uuid TEXT NOT NULL,
                    placer_name TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
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
        }
    }

    @Override
    public synchronized void upsertActiveContribution(UUID targetUuid, String targetName, UUID placerUuid, String placerName, long amount)
        throws SQLException {
        Optional<BountyContribution> existing = getActiveContribution(targetUuid, placerUuid);
        Instant now = Instant.now();
        if (existing.isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bounty_contributions
                SET amount = ?, target_name = ?, placer_name = ?, updated_at = ?
                WHERE id = ?
                """)) {
                statement.setLong(1, existing.get().amount() + amount);
                statement.setString(2, targetName);
                statement.setString(3, placerName);
                statement.setString(4, now.toString());
                statement.setLong(5, existing.get().id());
                statement.executeUpdate();
            }
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO bounty_contributions
            (target_uuid, target_name, placer_uuid, placer_name, amount, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, targetName);
            statement.setString(3, placerUuid.toString());
            statement.setString(4, placerName);
            statement.setLong(5, amount);
            statement.setString(6, ContributionStatus.ACTIVE.name());
            statement.setString(7, now.toString());
            statement.setString(8, now.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized Optional<BountyContribution> getActiveContribution(UUID targetUuid, UUID placerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT * FROM bounty_contributions
            WHERE target_uuid = ? AND placer_uuid = ? AND status = ?
            LIMIT 1
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, placerUuid.toString());
            statement.setString(3, ContributionStatus.ACTIVE.name());
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
            WHERE placer_uuid = ? AND status = ?
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
            SELECT target_uuid, MAX(target_name) AS target_name, COALESCE(SUM(amount), 0) AS total, COUNT(*) AS contributors
            FROM bounty_contributions
            WHERE target_uuid = ? AND status = ?
            GROUP BY target_uuid
            """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, ContributionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapTargetSummary(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized List<BountyTargetSummary> listActiveTargetSummaries(int limit, int offset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT target_uuid, MAX(target_name) AS target_name, COALESCE(SUM(amount), 0) AS total, COUNT(*) AS contributors
            FROM bounty_contributions
            WHERE status = ?
            GROUP BY target_uuid
            ORDER BY total DESC, target_name ASC
            LIMIT ? OFFSET ?
            """)) {
            statement.setString(1, ContributionStatus.ACTIVE.name());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
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
    public synchronized void recordClaim(UUID targetUuid, String targetName, UUID killerUuid, String killerName, long totalAmount, int sourceCount)
        throws SQLException {
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
            statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
        }
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

    private BountyContribution mapContribution(ResultSet resultSet) throws SQLException {
        return new BountyContribution(
            resultSet.getLong("id"),
            UUID.fromString(resultSet.getString("target_uuid")),
            resultSet.getString("target_name"),
            UUID.fromString(resultSet.getString("placer_uuid")),
            resultSet.getString("placer_name"),
            resultSet.getLong("amount"),
            ContributionStatus.valueOf(resultSet.getString("status")),
            Instant.parse(resultSet.getString("created_at")),
            Instant.parse(resultSet.getString("updated_at"))
        );
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
