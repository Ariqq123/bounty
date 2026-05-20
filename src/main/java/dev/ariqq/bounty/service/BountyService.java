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
import dev.ariqq.bounty.util.MoneyFormatter;
import dev.ariqq.bounty.util.Msg;
import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
        if (amount > BountyConfig.MAX_SAFE_ECONOMY_AMOUNT) {
            return ServiceResult.failure("Maximum supported bounty is " + MoneyFormatter.format(BountyConfig.MAX_SAFE_ECONOMY_AMOUNT) + ".");
        }
        ServiceResult poolCapacity = ensurePoolCanAccept(target, amount);
        if (poolCapacity != null) {
            return poolCapacity;
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
            repository.upsertActiveContribution(target.uuid(), target.name(), placerUuid, placerName, amount, false);
            long total = repository.getActiveTotalForTarget(target.uuid());
            announcePlacement(placerName, target.name(), amount, total);
            notifier.notifyBountyPlaced(placerName, target.name(), amount, total, false);
            return ServiceResult.success("Placed bounty of " + MoneyFormatter.format(amount) + " on " + target.name() + ". Total pool: " + MoneyFormatter.format(total) + ".");
        } catch (SQLException exception) {
            restoreDeposit(placerUuid, placerName, amount, "failed bounty placement rollback");
            logger.warning("Failed to place bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to save the bounty.");
        }
    }

    public ServiceResult adminAddBounty(KnownPlayer target, long amount, KnownPlayer placer) {
        if (amount > BountyConfig.MAX_SAFE_ECONOMY_AMOUNT) {
            return ServiceResult.failure("Maximum supported bounty is " + MoneyFormatter.format(BountyConfig.MAX_SAFE_ECONOMY_AMOUNT) + ".");
        }
        ServiceResult poolCapacity = ensurePoolCanAccept(target, amount);
        if (poolCapacity != null) {
            return poolCapacity;
        }
        if (amount < config().minAmount()) {
            return ServiceResult.failure("Minimum bounty is " + config().minAmount() + ".");
        }
        if (config().maxAmount() > 0 && amount > config().maxAmount()) {
            return ServiceResult.failure("Maximum bounty is " + config().maxAmount() + ".");
        }
        KnownPlayer effectivePlacer = placer == null ? new KnownPlayer(CONSOLE_UUID, CONSOLE_NAME) : placer;
        if (effectivePlacer.uuid().equals(target.uuid())) {
            return ServiceResult.failure("Admin bounty placer cannot be the same as the target.");
        }
        try {
            repository.upsertActiveContribution(target.uuid(), target.name(), effectivePlacer.uuid(), effectivePlacer.name(), amount, true);
            long total = repository.getActiveTotalForTarget(target.uuid());
            if (config().broadcastPlace()) {
                Msg.broadcast(Component.text(
                    effectivePlacer.name() + " added admin bounty of " + MoneyFormatter.format(amount) + " on " + target.name() + ". Total pool: " + MoneyFormatter.format(total) + ".",
                    NamedTextColor.GOLD
                ));
            }
            notifier.notifyBountyPlaced(effectivePlacer.name(), target.name(), amount, total, true);
            return ServiceResult.success("Added admin bounty. Total pool: " + MoneyFormatter.format(total) + ".");
        } catch (SQLException exception) {
            logger.warning("Failed to add admin bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to save the bounty.");
        }
    }

    public ServiceResult cancelOwnBounty(UUID placerUuid, String placerName, KnownPlayer target) {
        try {
            List<BountyContribution> targetContributions = repository.getActiveContributionsByTarget(target.uuid());
            List<BountyContribution> refundableContributions = targetContributions.stream()
                .filter(active -> active.placerUuid().equals(placerUuid))
                .filter(active -> !active.adminFunded())
                .toList();
            if (refundableContributions.isEmpty()) {
                boolean hasAdminFundedAttribution = repository.getActiveContributionsByTarget(target.uuid()).stream()
                    .anyMatch(active -> active.placerUuid().equals(placerUuid) && active.adminFunded());
                if (hasAdminFundedAttribution) {
                    return ServiceResult.failure("Admin-funded bounty contributions cannot be cancelled by players.");
                }
                return ServiceResult.failure("You do not have an active bounty on " + target.name() + ".");
            }

            List<RefundCancellation> refundCancellations = new ArrayList<>();
            long totalRefund = 0L;
            for (BountyContribution active : refundableContributions) {
                if (!isSafeEconomyAmount(active.amount())) {
                    logger.warning("Unsupported bounty amount on contribution " + active.id() + ": " + active.amount());
                    return ServiceResult.failure("This bounty amount is not supported by the economy and cannot be refunded safely.");
                }
                long refund = config().refundAmount(active.amount());
                if (!isSafeEconomyAmount(refund)) {
                    logger.warning("Unsupported refund amount on contribution " + active.id() + ": " + refund);
                    return ServiceResult.failure("This bounty amount is not supported by the economy and cannot be refunded safely.");
                }
                try {
                    totalRefund = Math.addExact(totalRefund, refund);
                } catch (ArithmeticException exception) {
                    logger.warning("Refund total overflow on target " + target.uuid() + " for placer " + placerUuid + ".");
                    return ServiceResult.failure("This bounty amount is not supported by the economy and cannot be refunded safely.");
                }
                refundCancellations.add(new RefundCancellation(active.id(), refund));
            }

            List<RefundCancellation> depositedRefunds = new ArrayList<>();
            for (RefundCancellation cancellation : refundCancellations) {
                if (!economy.deposit(placerUuid, placerName, cancellation.refundAmount())) {
                    for (RefundCancellation deposited : depositedRefunds) {
                        compensateDeposit(placerUuid, placerName, deposited.refundAmount(), "cancelled bounty refund rollback");
                    }
                    logger.warning("Refund deposit failed for " + placerName + " on bounty " + cancellation.contributionId());
                    return ServiceResult.failure("Failed to refund your cancelled bounty.");
                }
                depositedRefunds.add(cancellation);
            }

            try {
                int cancelled = repository.transitionContributionStatuses(
                    refundCancellations.stream().map(RefundCancellation::contributionId).toList(),
                    ContributionStatus.ACTIVE,
                    ContributionStatus.CANCELLED
                );
                if (cancelled != refundCancellations.size()) {
                    for (RefundCancellation deposited : depositedRefunds) {
                        compensateDeposit(placerUuid, placerName, deposited.refundAmount(), "cancelled bounty status mismatch rollback");
                    }
                    return ServiceResult.failure("Your bounty changed before it could be cancelled.");
                }
            } catch (SQLException exception) {
                for (RefundCancellation deposited : depositedRefunds) {
                    compensateDeposit(placerUuid, placerName, deposited.refundAmount(), "cancelled bounty rollback");
                }
                throw exception;
            }

            notifier.notifyBountyCancelled(placerName, target.name(), totalRefund);
            return ServiceResult.success("Cancelled your bounty on " + target.name() + ". Refunded " + MoneyFormatter.format(totalRefund) + ".");
        } catch (SQLException exception) {
            logger.warning("Failed to cancel bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to cancel the bounty.");
        }
    }

    public ServiceResult adminRemoveTarget(KnownPlayer target) {
        try {
            List<BountyContribution> contributions = repository.getActiveContributionsByTarget(target.uuid());
            if (contributions.isEmpty()) {
                return ServiceResult.failure("No active bounty found for " + target.name() + ".");
            }

            boolean hasRefundablePlayerContribution = contributions.stream()
                .anyMatch(contribution ->
                    !contribution.adminFunded()
                        && !CONSOLE_UUID.equals(contribution.placerUuid())
                        && isSafeEconomyAmount(contribution.amount())
                );
            if (hasRefundablePlayerContribution) {
                return ServiceResult.failure(
                    "Cannot remove active refundable player-funded contributions from " + target.name() + ". Use /bounty admin refund " + target.name() + " instead."
                );
            }

            int removed = 0;
            int failed = 0;
            List<Long> removableIds = contributions.stream()
                .map(BountyContribution::id)
                .toList();
            removed = repository.transitionContributionStatuses(removableIds, ContributionStatus.ACTIVE, ContributionStatus.REMOVED);
            if (removed != removableIds.size()) {
                failed = removableIds.size() - removed;
            }
            if (removed == 0) {
                if (failed > 0) {
                    return ServiceResult.failure("No non-refundable contribution could be removed. " + failed + " contribution(s) remain active.");
                }
                return ServiceResult.failure("No active bounty could be removed for " + target.name() + ".");
            }
            notifier.notifyAdminTargetRemoved(target.name(), removed);
            return partialAwareSuccess(
                "Removed " + removed + " non-refundable contribution(s) from " + target.name() + ".",
                failed
            );
        } catch (SQLException exception) {
            logger.warning("Failed to remove target bounty: " + exception.getMessage());
            return ServiceResult.failure("Failed to remove target bounty.");
        }
    }

    public ServiceResult adminRefundTarget(KnownPlayer target, KnownPlayer placer) {
        try {
            List<BountyContribution> contributions = placer == null
                ? repository.getActiveContributionsByTarget(target.uuid())
                : repository.getActiveContributionsByTarget(target.uuid()).stream()
                    .filter(contribution -> contribution.placerUuid().equals(placer.uuid()))
                    .toList();

            if (contributions.isEmpty()) {
                return ServiceResult.failure("No active contribution matched that request.");
            }

            long refunded = 0L;
            int closed = 0;
            int refundedContributions = 0;
            int failed = 0;
            List<BountyContribution> refundableContributions = new ArrayList<>();
            List<Long> closableContributionIds = new ArrayList<>();
            for (BountyContribution contribution : contributions) {
                if (!contribution.adminFunded() && !CONSOLE_UUID.equals(contribution.placerUuid())) {
                    if (!isSafeEconomyAmount(contribution.amount())) {
                        logger.warning("Unsupported refund amount on contribution " + contribution.id() + ": " + contribution.amount());
                        failed++;
                        continue;
                    }
                    if (!economy.deposit(contribution.placerUuid(), contribution.placerName(), contribution.amount())) {
                        logger.warning("Refund deposit failed for " + contribution.placerName() + " on bounty " + contribution.id());
                        failed++;
                        continue;
                    }
                    refundableContributions.add(contribution);
                    closableContributionIds.add(contribution.id());
                    refunded += contribution.amount();
                    refundedContributions++;
                } else {
                    closableContributionIds.add(contribution.id());
                }
            }

            if (!closableContributionIds.isEmpty()) {
                try {
                    int closedRows = repository.transitionContributionStatuses(closableContributionIds, ContributionStatus.ACTIVE, ContributionStatus.REFUNDED);
                    if (closedRows != closableContributionIds.size()) {
                        for (BountyContribution contribution : refundableContributions) {
                            compensateDeposit(contribution.placerUuid(), contribution.placerName(), contribution.amount(), "admin refund batch rollback");
                        }
                        refunded -= refundableContributions.stream().mapToLong(BountyContribution::amount).sum();
                        refundedContributions = 0;
                        failed += closableContributionIds.size();
                    } else {
                        closed += closedRows;
                    }
                } catch (SQLException exception) {
                    for (BountyContribution contribution : refundableContributions) {
                        compensateDeposit(contribution.placerUuid(), contribution.placerName(), contribution.amount(), "admin refund batch rollback");
                    }
                    throw exception;
                }
            }
            if (closed == 0) {
                if (failed > 0) {
                    return ServiceResult.failure("No contribution could be processed. " + failed + " contribution(s) remain active.");
                }
                return ServiceResult.failure("No contribution could be refunded.");
            }
            notifier.notifyAdminRefund(target.name(), refunded, closed);
            if (refundedContributions == 0) {
                return partialAwareSuccess(
                    "Closed " + closed + " contribution(s). No player funds were refunded.",
                    failed
                );
            }
            return partialAwareSuccess(
                "Closed " + closed + " contribution(s). Refunded "
                    + MoneyFormatter.format(refunded)
                    + " across "
                    + refundedContributions
                    + " player contribution(s).",
                failed
            );
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

            long total = 0L;
            for (BountyContribution contribution : contributions) {
                if (!isSafeEconomyAmount(contribution.amount())) {
                    logger.warning("Unsupported claim amount on contribution " + contribution.id() + ": " + contribution.amount());
                    return ClaimResult.failure("This bounty amount is not supported by the economy and cannot be claimed safely.");
                }
                try {
                    total = Math.addExact(total, contribution.amount());
                } catch (ArithmeticException exception) {
                    return ClaimResult.failure("This bounty reward exceeds the maximum supported economy amount.");
                }
            }
            if (!isSafeEconomyAmount(total)) {
                return ClaimResult.failure("This bounty reward exceeds the maximum supported economy amount.");
            }
            if (!economy.deposit(killerUuid, killerName, total)) {
                return ClaimResult.failure("Failed to pay out bounty reward.");
            }

            try {
                List<Long> contributionIds = contributions.stream().map(BountyContribution::id).toList();
                int claimed = repository.finalizeClaim(
                    contributionIds,
                    targetUuid,
                    targetName,
                    killerUuid,
                    killerName,
                    total,
                    contributions.size(),
                    Instant.now()
                );
                if (claimed != contributionIds.size()) {
                    compensateDeposit(killerUuid, killerName, total, "claim status mismatch rollback");
                    return ClaimResult.failure("The bounty changed before it could be claimed.");
                }
            } catch (SQLException exception) {
                compensateDeposit(killerUuid, killerName, total, "claim rollback");
                throw exception;
            }

            if (config().broadcastClaim() && Bukkit.getServer() != null) {
                Msg.broadcast(Msg.ok(
                    killerName + " claimed " + MoneyFormatter.format(total) + " by killing " + targetName + "."
                ));
            }
            notifier.notifyBountyClaimed(killerName, targetName, total, contributions.size());
            return ClaimResult.success("Claimed bounty of " + MoneyFormatter.format(total) + ".", total, targetName);
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
        if (page <= 0 || pageSize <= 0) {
            return Collections.emptyList();
        }
        try {
            long offsetLong = (long) (page - 1) * pageSize;
            if (offsetLong > Integer.MAX_VALUE) {
                return Collections.emptyList();
            }
            int offset = (int) offsetLong;
            return repository.listActiveTargetSummaries(pageSize, offset);
        } catch (SQLException exception) {
            logger.warning("Failed to list active targets: " + exception.getMessage());
            return Collections.emptyList();
        }
    }

    public int countActiveTargets() {
        try {
            return repository.countActiveTargets();
        } catch (SQLException exception) {
            logger.warning("Failed to count active targets: " + exception.getMessage());
            return 0;
        }
    }

    public List<BountyTargetSummary> topActiveTargets(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
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
        java.util.Map<UUID, KnownPlayer> players = new java.util.LinkedHashMap<>();
        Bukkit.getOnlinePlayers().forEach(player ->
            players.put(player.getUniqueId(), new KnownPlayer(player.getUniqueId(), player.getName()))
        );
        java.util.Arrays.stream(Bukkit.getOfflinePlayers())
            .filter(player -> player.getName() != null)
            .forEach(player -> players.putIfAbsent(player.getUniqueId(), new KnownPlayer(player.getUniqueId(), player.getName())));
        return players.values().stream()
            .sorted(Comparator.comparing(KnownPlayer::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public BountyConfig config() {
        return configSupplier.get();
    }

    public String formatSummary(BountyTargetSummary summary) {
        return summary.targetName() + "  —  " + MoneyFormatter.format(summary.totalAmount()) + " (" + summary.contributorCount() + " contributor(s))";
    }

    public void sendInfo(CommandSender sender, KnownPlayer target) {
        Optional<BountyTargetSummary> summary = getTargetSummary(target.uuid());
        if (summary.isEmpty()) {
            Msg.send(sender, Msg.err("No active bounty on " + target.name() + "."));
            return;
        }
        BountyTargetSummary targetSummary = summary.get();
        Msg.send(sender, Msg.header("Bounty Details"));
        Msg.send(sender, Msg.info("Target: " + target.name()));
        Msg.send(sender, Msg.info("Pool: " + MoneyFormatter.format(targetSummary.totalAmount())));
        Msg.send(sender, Msg.info("Contributors: " + targetSummary.contributorCount()));
    }

    public ServiceResult sendDiscordTest(String requestedBy) {
        if (!config().discordEnabled()) {
            return ServiceResult.failure("Discord integration is disabled in config.");
        }
        String webhookUrl = config().discordWebhookUrl();
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return ServiceResult.failure("Discord webhook URL is not configured.");
        }
        if (!isValidWebhookUrl(webhookUrl)) {
            return ServiceResult.failure("Discord webhook URL is invalid.");
        }
        notifier.notifyTestMessage(requestedBy);
        return ServiceResult.success("Discord test embed queued.");
    }

    private void announcePlacement(String placerName, String targetName, long amount, long total) {
        if (Bukkit.getServer() == null) {
            return;
        }
        Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer != null) {
            Msg.send(targetPlayer, Msg.err(
                placerName + " placed " + MoneyFormatter.format(amount) + " on you. Total pool: " + MoneyFormatter.format(total) + "."
            ));
        }
        if (config().broadcastPlace()) {
            Msg.broadcast(Msg.info(
                placerName + " placed " + MoneyFormatter.format(amount) + " on " + targetName + ". Total pool: " + MoneyFormatter.format(total) + "."
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

    private void restoreDeposit(UUID playerUuid, String playerName, long amount, String context) {
        if (!economy.deposit(playerUuid, playerName, amount)) {
            logger.severe("Failed to restore " + context + " for " + playerName + " amount=" + amount + ".");
        }
    }

    private boolean isValidWebhookUrl(String webhookUrl) {
        try {
            URI uri = URI.create(webhookUrl.trim());
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                && scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null
                && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void compensateDeposit(UUID playerUuid, String playerName, long amount, String context) {
        if (!economy.withdraw(playerUuid, playerName, amount)) {
            logger.severe("Failed to compensate " + context + " for " + playerName + " amount=" + amount + ".");
        }
    }

    private ServiceResult ensurePoolCanAccept(KnownPlayer target, long amount) {
        try {
            long currentTotal = repository.getActiveTotalForTarget(target.uuid());
            if (!isSafeEconomyAmount(currentTotal)) {
                return ServiceResult.failure("Current bounty pool is not supported by the economy.");
            }
            long newTotal = Math.addExact(currentTotal, amount);
            if (newTotal > BountyConfig.MAX_SAFE_ECONOMY_AMOUNT) {
                return ServiceResult.failure("Maximum supported total bounty pool is " + MoneyFormatter.format(BountyConfig.MAX_SAFE_ECONOMY_AMOUNT) + ".");
            }
            return null;
        } catch (ArithmeticException exception) {
            return ServiceResult.failure("Maximum supported total bounty pool is " + MoneyFormatter.format(BountyConfig.MAX_SAFE_ECONOMY_AMOUNT) + ".");
        } catch (SQLException exception) {
            logger.warning("Failed to load current bounty pool: " + exception.getMessage());
            return ServiceResult.failure("Failed to load the current bounty pool.");
        }
    }

    private ServiceResult partialAwareSuccess(String baseMessage, int failedContributions) {
        if (failedContributions <= 0) {
            return ServiceResult.success(baseMessage);
        }
        return ServiceResult.success(
            baseMessage + " " + failedContributions + " contribution(s) could not be processed and remain active."
        );
    }

    private boolean isSafeEconomyAmount(long amount) {
        return amount >= 0L && amount <= BountyConfig.MAX_SAFE_ECONOMY_AMOUNT;
    }

    private record RefundCancellation(long contributionId, long refundAmount) {
    }
}
