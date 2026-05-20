package dev.ariqq.bounty.service;

import dev.ariqq.bounty.BountyPlugin;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BountyService {
    private static final UUID CONSOLE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String CONSOLE_NAME = "CONSOLE";

    private final Logger logger;
    private final BountyRepository repository;
    private final EconomyAdapter economy;
    private final BountyNotifier notifier;
    private final Supplier<BountyConfig> configSupplier;

    public BountyService(
        BountyPlugin plugin,
        Logger logger,
        BountyRepository repository,
        EconomyAdapter economy,
        BountyNotifier notifier,
        Supplier<BountyConfig> configSupplier
    ) {
        this.logger = logger;
        this.repository = repository;
        this.economy = economy;
        this.notifier = notifier;
        this.configSupplier = configSupplier;
    }

    public ServiceResult placeBounty(UUID placerUuid, String placerName, KnownPlayer target, long amount) {
        if (placerUuid.equals(target.uuid())) {
            return ServiceResult.failure("You cannot place a bounty on yourself.");
        }
        if (amount < config().minAmount()) {
            return ServiceResult.failure("Minimum bounty is " + config().minAmount() + ".");
        }
        if (config().maxAmount() > 0 && amount > config().maxAmount()) {
            return ServiceResult.failure("Maximum bounty is " + config().maxAmount() + ".");
        }
        if (!economy.has(placerUuid, amount)) {
            return ServiceResult.failure("You do not have enough money to fund that bounty.");
        }
        if (!economy.withdraw(placerUuid, placerName, amount)) {
            return ServiceResult.failure("Failed to withdraw money from your account.");
        }

        try {
            repository.upsertActiveContribution(target.uuid(), target.name(), placerUuid, placerName, amount);
            long total = repository.getActiveTotalForTarget(target.uuid());
            announcePlacement(placerName, target.name(), amount, total);
            notifier.notifyBountyPlaced(placerName, target.name(), amount, total, false);
            return ServiceResult.success("Placed bounty of " + amount + " on " + target.name() + ". Total pool: " + total + ".");
        } catch (SQLException exception) {
            economy.deposit(placerUuid, placerName, amount);
            logger.warning("Failed to place bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to save the bounty.");
        }
    }

    public ServiceResult adminAddBounty(KnownPlayer target, long amount, KnownPlayer placer) {
        if (amount < config().minAmount()) {
            return ServiceResult.failure("Minimum bounty is " + config().minAmount() + ".");
        }
        KnownPlayer effectivePlacer = placer == null ? new KnownPlayer(CONSOLE_UUID, CONSOLE_NAME) : placer;
        try {
            repository.upsertActiveContribution(target.uuid(), target.name(), effectivePlacer.uuid(), effectivePlacer.name(), amount);
            long total = repository.getActiveTotalForTarget(target.uuid());
            if (config().broadcastPlace()) {
                Bukkit.broadcast(Component.text(
                    effectivePlacer.name() + " added admin bounty of " + amount + " on " + target.name() + ". Total pool: " + total + ".",
                    NamedTextColor.GOLD
                ));
            }
            notifier.notifyBountyPlaced(effectivePlacer.name(), target.name(), amount, total, true);
            return ServiceResult.success("Added admin bounty. Total pool: " + total + ".");
        } catch (SQLException exception) {
            logger.warning("Failed to add admin bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to save the bounty.");
        }
    }

    public ServiceResult cancelOwnBounty(UUID placerUuid, String placerName, KnownPlayer target) {
        try {
            Optional<BountyContribution> contribution = repository.getActiveContribution(target.uuid(), placerUuid);
            if (contribution.isEmpty()) {
                return ServiceResult.failure("You do not have an active bounty on " + target.name() + ".");
            }

            BountyContribution active = contribution.get();
            long refund = config().refundAmount(active.amount());
            if (!economy.deposit(placerUuid, placerName, refund)) {
                logger.warning("Refund deposit failed for " + placerName + " on bounty " + active.id());
                return ServiceResult.failure("Failed to refund your cancelled bounty.");
            }
            try {
                repository.updateContributionStatus(active.id(), ContributionStatus.CANCELLED);
            } catch (SQLException exception) {
                compensateDeposit(placerUuid, placerName, refund, "cancelled bounty rollback");
                throw exception;
            }

            notifier.notifyBountyCancelled(placerName, target.name(), refund);
            return ServiceResult.success("Cancelled your bounty on " + target.name() + ". Refunded " + refund + ".");
        } catch (SQLException exception) {
            logger.warning("Failed to cancel bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to cancel the bounty.");
        }
    }

    public ServiceResult adminRemoveTarget(KnownPlayer target) {
        try {
            int rows = repository.updateTargetContributionsStatus(target.uuid(), ContributionStatus.REMOVED);
            if (rows == 0) {
                return ServiceResult.failure("No active bounty found for " + target.name() + ".");
            }
            notifier.notifyAdminTargetRemoved(target.name(), rows);
            return ServiceResult.success("Removed " + rows + " active contribution(s) from " + target.name() + ".");
        } catch (SQLException exception) {
            logger.warning("Failed to remove target bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to remove target bounty.");
        }
    }

    public ServiceResult adminRefundTarget(KnownPlayer target, KnownPlayer placer) {
        try {
            List<BountyContribution> contributions = placer == null
                ? repository.getActiveContributionsByTarget(target.uuid())
                : repository.getActiveContribution(target.uuid(), placer.uuid()).map(List::of).orElse(List.of());

            if (contributions.isEmpty()) {
                return ServiceResult.failure("No active contribution matched that request.");
            }

            long refunded = 0L;
            int updated = 0;
            for (BountyContribution contribution : contributions) {
                if (!CONSOLE_UUID.equals(contribution.placerUuid())) {
                    if (!economy.deposit(contribution.placerUuid(), contribution.placerName(), contribution.amount())) {
                        logger.warning("Refund deposit failed for " + contribution.placerName() + " on bounty " + contribution.id());
                        continue;
                    }
                    try {
                        repository.updateContributionStatus(contribution.id(), ContributionStatus.REFUNDED);
                        refunded += contribution.amount();
                        updated++;
                    } catch (SQLException exception) {
                        compensateDeposit(contribution.placerUuid(), contribution.placerName(), contribution.amount(), "admin refund rollback");
                        throw exception;
                    }
                } else {
                    repository.updateContributionStatus(contribution.id(), ContributionStatus.REFUNDED);
                    updated++;
                }
            }
            if (updated == 0) {
                return ServiceResult.failure("No contribution could be refunded.");
            }
            notifier.notifyAdminRefund(target.name(), refunded, updated);
            return ServiceResult.success("Refunded " + refunded + " across " + updated + " contribution(s).");
        } catch (SQLException exception) {
            logger.warning("Failed to refund bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to refund bounty.");
        }
    }

    public ClaimResult claimIfEligible(Player killer, Player target) {
        if (killer == null || target == null) {
            return ClaimResult.failure("No valid killer.");
        }
        return claimIfEligible(killer.getUniqueId(), killer.getName(), target.getUniqueId(), target.getName());
    }

    ClaimResult claimIfEligible(UUID killerUuid, String killerName, UUID targetUuid, String targetName) {
        if (killerUuid.equals(targetUuid)) {
            return ClaimResult.failure("Self-kill is not eligible.");
        }

        try {
            List<BountyContribution> contributions = repository.getActiveContributionsByTarget(targetUuid);
            if (contributions.isEmpty()) {
                return ClaimResult.failure("No bounty.");
            }
            if (isOnCooldown(killerUuid, targetUuid)) {
                return ClaimResult.failure("This killer-target pair is still on cooldown.");
            }

            long total = contributions.stream().mapToLong(BountyContribution::amount).sum();
            if (!economy.deposit(killerUuid, killerName, total)) {
                return ClaimResult.failure("Failed to pay out bounty reward.");
            }

            try {
                repository.recordClaim(targetUuid, targetName, killerUuid, killerName, total, contributions.size());
                int claimed = repository.updateTargetContributionsStatus(targetUuid, ContributionStatus.CLAIMED);
                if (claimed <= 0) {
                    throw new SQLException("No active contributions were marked claimed.");
                }
                repository.upsertAbuseLock(killerUuid, targetUuid, Instant.now());
            } catch (SQLException exception) {
                compensateDeposit(killerUuid, killerName, total, "claim rollback");
                throw exception;
            }

            if (config().broadcastClaim() && Bukkit.getServer() != null) {
                Bukkit.broadcast(Component.text(
                    killerName + " claimed bounty of " + total + " by killing " + targetName + ".",
                    NamedTextColor.GREEN
                ));
            }
            notifier.notifyBountyClaimed(killerName, targetName, total, contributions.size());
            return ClaimResult.success("Claimed bounty of " + total + ".", total, targetName);
        } catch (SQLException exception) {
            logger.warning("Failed to process bounty claim: " + exception.getMessage());
            return ClaimResult.failure("Failed to process bounty claim.");
        }
    }

    public Optional<KnownPlayer> resolveKnownPlayer(String input) {
        if (input == null) {
            return Optional.empty();
        }
        input = input.trim();
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return Optional.of(new KnownPlayer(online.getUniqueId(), online.getName()));
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null && cached.getName() != null) {
            return Optional.of(new KnownPlayer(cached.getUniqueId(), cached.getName()));
        }

        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.getName().equalsIgnoreCase(input)) {
                return Optional.of(new KnownPlayer(player.getUniqueId(), player.getName()));
            }
        }
        return Optional.empty();
    }

    public List<BountyTargetSummary> listActiveTargets(int page, int pageSize) {
        try {
            int offset = Math.max(0, (page - 1) * pageSize);
            return repository.listActiveTargetSummaries(pageSize, offset);
        } catch (SQLException exception) {
            logger.warning("Failed to list active targets: " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    public List<BountyTargetSummary> topActiveTargets(int limit) {
        try {
            return repository.listTopTargetSummaries(limit);
        } catch (SQLException exception) {
            logger.warning("Failed to list top targets: " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    public Optional<BountyTargetSummary> getTargetSummary(UUID targetUuid) {
        try {
            return repository.getTargetSummary(targetUuid);
        } catch (SQLException exception) {
            logger.warning("Failed to load target summary: " + exception.getMessage());
            return Optional.empty();
        }
    }

    public List<BountyContribution> getPlayerContributions(UUID placerUuid) {
        try {
            return repository.getActiveContributionsByPlacer(placerUuid);
        } catch (SQLException exception) {
            logger.warning("Failed to load player contributions: " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    public List<BountyClaim> getClaimHistory(UUID targetUuid) {
        try {
            return repository.getClaimHistory(targetUuid, 10);
        } catch (SQLException exception) {
            logger.warning("Failed to load claim history: " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    public List<KnownPlayer> listKnownPlayers() {
        List<KnownPlayer> players = Bukkit.getOnlinePlayers().stream()
            .map(player -> new KnownPlayer(player.getUniqueId(), player.getName()))
            .toList();
        if (!players.isEmpty()) {
            return players;
        }
        return java.util.Arrays.stream(Bukkit.getOfflinePlayers())
            .filter(player -> player.getName() != null)
            .map(player -> new KnownPlayer(player.getUniqueId(), player.getName()))
            .sorted(Comparator.comparing(KnownPlayer::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public BountyConfig config() {
        return configSupplier.get();
    }

    public String formatSummary(BountyTargetSummary summary) {
        return summary.targetName() + " - " + summary.totalAmount() + " (" + summary.contributorCount() + " contributors)";
    }

    public void sendInfo(CommandSender sender, KnownPlayer target) {
        Optional<BountyTargetSummary> summary = getTargetSummary(target.uuid());
        if (summary.isEmpty()) {
            sender.sendMessage(Component.text("No active bounty on " + target.name() + ".", NamedTextColor.RED));
            return;
        }
        BountyTargetSummary targetSummary = summary.get();
        sender.sendMessage(Component.text("Bounty on " + target.name() + ": " + targetSummary.totalAmount(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Contributors: " + targetSummary.contributorCount(), NamedTextColor.YELLOW));
    }

    private void announcePlacement(String placerName, String targetName, long amount, long total) {
        if (Bukkit.getServer() == null) {
            return;
        }
        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer != null) {
            targetPlayer.sendMessage(Component.text(
                placerName + " placed bounty of " + amount + " on you. Total pool: " + total + ".",
                NamedTextColor.RED
            ));
        }
        if (config().broadcastPlace()) {
            Bukkit.broadcast(Component.text(
                placerName + " placed bounty of " + amount + " on " + targetName + ". Total pool: " + total + ".",
                NamedTextColor.GOLD
            ));
        }
    }

    private boolean isOnCooldown(UUID killerUuid, UUID targetUuid) throws SQLException {
        Optional<Instant> lastClaim = repository.getLastClaimForPair(killerUuid, targetUuid);
        if (lastClaim.isEmpty()) {
            return false;
        }
        return lastClaim.get().plusSeconds(config().claimCooldownSecondsPerPair()).isAfter(Instant.now());
    }

    private void compensateDeposit(UUID playerUuid, String playerName, long amount, String context) {
        if (!economy.withdraw(playerUuid, playerName, amount)) {
            logger.severe("Failed to compensate " + context + " for " + playerName + " amount=" + amount + ".");
        }
    }
}
