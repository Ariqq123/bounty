package dev.ariqq.bounty.command;

import dev.ariqq.bounty.BountyPlugin;
import dev.ariqq.bounty.gui.BountyGuiManager;
import dev.ariqq.bounty.model.BountyClaim;
import dev.ariqq.bounty.model.BountyContribution;
import dev.ariqq.bounty.model.BountyTargetSummary;
import dev.ariqq.bounty.model.KnownPlayer;
import dev.ariqq.bounty.model.ServiceResult;
import dev.ariqq.bounty.service.BountyService;
import dev.ariqq.bounty.util.MoneyFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class BountyCommand implements CommandExecutor, TabCompleter {
    private final BountyPlugin plugin;
    private final BountyService bountyService;
    private final BountyGuiManager guiManager;

    public BountyCommand(BountyPlugin plugin, BountyService bountyService, BountyGuiManager guiManager) {
        this.plugin = plugin;
        this.bountyService = bountyService;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bounty.use")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                guiManager.openMain(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "place" -> handlePlace(sender, args);
            case "info" -> handleInfo(sender, args);
            case "list" -> handleList(sender, args);
            case "top" -> handleTop(sender, args);
            case "my" -> handleMy(sender);
            case "cancel" -> handleCancel(sender, args);
            case "admin" -> handleAdmin(sender, args);
            case "reload" -> handleReload(sender);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("place", "info", "list", "top", "my", "cancel", "admin", "reload"), args[0]);
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0])) {
            return filter(List.of("add", "remove", "refund", "history", "testdiscord"), args[1]);
        }
        return List.of();
    }

    private boolean handlePlace(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can place bounty.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("bounty.place")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/bounty place <player> <amount>", NamedTextColor.YELLOW));
            return true;
        }
        Optional<KnownPlayer> target = bountyService.resolveKnownPlayer(args[1]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + args[1], NamedTextColor.RED));
            return true;
        }
        OptionalLong amount = parsePositiveLong(args[2], sender, "Amount");
        if (amount.isEmpty()) {
            return true;
        }
        ServiceResult result = bountyService.placeBounty(player.getUniqueId(), player.getName(), target.get(), amount.getAsLong());
        sender.sendMessage(Component.text(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("/bounty info <player>", NamedTextColor.YELLOW));
            return true;
        }
        Optional<KnownPlayer> target = bountyService.resolveKnownPlayer(args[1]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + args[1], NamedTextColor.RED));
            return true;
        }
        bountyService.sendInfo(sender, target.get());
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length > 1) {
            OptionalLong parsed = parsePositiveLong(args[1], sender, "Page");
            if (parsed.isEmpty()) {
                return true;
            }
            page = safeInt(parsed.getAsLong(), sender, "Page");
            if (page <= 0) {
                return true;
            }
        }
        List<BountyTargetSummary> summaries = bountyService.listActiveTargets(page, bountyService.config().guiPageSize());
        sender.sendMessage(Component.text("Active bounties page " + page, NamedTextColor.GOLD));
        if (summaries.isEmpty()) {
            sender.sendMessage(Component.text("No active bounties found.", NamedTextColor.GRAY));
            return true;
        }
        for (BountyTargetSummary summary : summaries) {
            sender.sendMessage(Component.text("- " + bountyService.formatSummary(summary), NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean handleTop(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length > 1) {
            OptionalLong parsed = parsePositiveLong(args[1], sender, "Limit");
            if (parsed.isEmpty()) {
                return true;
            }
            limit = safeInt(parsed.getAsLong(), sender, "Limit");
            if (limit <= 0) {
                return true;
            }
        }
        List<BountyTargetSummary> summaries = bountyService.topActiveTargets(limit);
        sender.sendMessage(Component.text("Top bounties", NamedTextColor.GOLD));
        if (summaries.isEmpty()) {
            sender.sendMessage(Component.text("No active bounties found.", NamedTextColor.GRAY));
            return true;
        }
        int rank = 1;
        for (BountyTargetSummary summary : summaries) {
            sender.sendMessage(Component.text(rank++ + ". " + bountyService.formatSummary(summary), NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean handleMy(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can view their bounties.", NamedTextColor.RED));
            return true;
        }
        List<BountyContribution> contributions = bountyService.getPlayerContributions(player.getUniqueId());
        sender.sendMessage(Component.text("Your active bounties", NamedTextColor.GOLD));
        if (contributions.isEmpty()) {
            sender.sendMessage(Component.text("You do not have active bounties.", NamedTextColor.GRAY));
            return true;
        }
        for (BountyContribution contribution : contributions) {
            sender.sendMessage(Component.text("- " + contribution.targetName() + ": " + MoneyFormatter.format(contribution.amount()), NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean handleCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can cancel their bounties.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("bounty.cancel.own")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/bounty cancel <player>", NamedTextColor.YELLOW));
            return true;
        }
        Optional<KnownPlayer> target = bountyService.resolveKnownPlayer(args[1]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + args[1], NamedTextColor.RED));
            return true;
        }
        ServiceResult result = bountyService.cancelOwnBounty(player.getUniqueId(), player.getName(), target.get());
        sender.sendMessage(Component.text(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length == 2 && "testdiscord".equalsIgnoreCase(args[1])) {
            return handleAdminTestDiscord(sender);
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/bounty admin <add|remove|refund|history> ...", NamedTextColor.YELLOW));
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "add" -> handleAdminAdd(sender, args);
            case "remove" -> handleAdminRemove(sender, args);
            case "refund" -> handleAdminRefund(sender, args);
            case "history" -> handleAdminHistory(sender, args);
            case "testdiscord" -> handleAdminTestDiscord(sender);
            default -> true;
        };
    }

    private boolean handleAdminAdd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bounty.admin.manage")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("/bounty admin add <player> <amount> [placer]", NamedTextColor.YELLOW));
            return true;
        }
        Optional<KnownPlayer> target = bountyService.resolveKnownPlayer(args[2]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + args[2], NamedTextColor.RED));
            return true;
        }
        OptionalLong amount = parsePositiveLong(args[3], sender, "Amount");
        if (amount.isEmpty()) {
            return true;
        }
        Optional<KnownPlayer> placer = Optional.empty();
        if (args.length >= 5) {
            placer = bountyService.resolveKnownPlayer(args[4]);
            if (placer.isEmpty()) {
                sender.sendMessage(Component.text("Unknown player: " + args[4], NamedTextColor.RED));
                return true;
            }
        }
        ServiceResult result = bountyService.adminAddBounty(target.get(), amount.getAsLong(), placer.orElse(null));
        sender.sendMessage(Component.text(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleAdminRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bounty.admin.manage")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        Optional<KnownPlayer> target = bountyService.resolveKnownPlayer(args[2]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + args[2], NamedTextColor.RED));
            return true;
        }
        ServiceResult result = bountyService.adminRemoveTarget(target.get());
        sender.sendMessage(Component.text(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleAdminRefund(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bounty.admin.manage")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        Optional<KnownPlayer> target = bountyService.resolveKnownPlayer(args[2]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + args[2], NamedTextColor.RED));
            return true;
        }
        Optional<KnownPlayer> placer = Optional.empty();
        if (args.length >= 4) {
            placer = bountyService.resolveKnownPlayer(args[3]);
            if (placer.isEmpty()) {
                sender.sendMessage(Component.text("Unknown player: " + args[3], NamedTextColor.RED));
                return true;
            }
        }
        ServiceResult result = bountyService.adminRefundTarget(target.get(), placer.orElse(null));
        sender.sendMessage(Component.text(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleAdminHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bounty.admin.history")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        Optional<KnownPlayer> target = bountyService.resolveKnownPlayer(args[2]);
        if (target.isEmpty()) {
            sender.sendMessage(Component.text("Unknown player: " + args[2], NamedTextColor.RED));
            return true;
        }
        List<BountyClaim> claims = bountyService.getClaimHistory(target.get().uuid());
        sender.sendMessage(Component.text("Claim history for " + target.get().name(), NamedTextColor.GOLD));
        if (claims.isEmpty()) {
            sender.sendMessage(Component.text("No claim history found.", NamedTextColor.GRAY));
            return true;
        }
        for (BountyClaim claim : claims) {
            sender.sendMessage(Component.text(
                "- " + claim.killerName() + " claimed " + dev.ariqq.bounty.util.MoneyFormatter.format(claim.totalAmount()) + " from " + claim.sourceCount() + " contribution(s).",
                NamedTextColor.YELLOW
            ));
        }
        return true;
    }

    private boolean handleAdminTestDiscord(CommandSender sender) {
        if (!sender.hasPermission("bounty.admin.manage")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        ServiceResult result = bountyService.sendDiscordTest(sender.getName());
        sender.sendMessage(Component.text(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("bounty.admin.reload")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        plugin.reloadBountyConfig();
        sender.sendMessage(Component.text("Bounty config reloaded.", NamedTextColor.GREEN));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        List<String> lines = Arrays.asList(
            "/bounty",
            "/bounty place <player> <amount>",
            "/bounty info <player>",
            "/bounty list [page]",
            "/bounty top [limit]",
            "/bounty my",
            "/bounty cancel <player>",
            "/bounty admin testdiscord"
        );
        sender.sendMessage(Component.text("Bounty commands", NamedTextColor.GOLD));
        for (String line : lines) {
            sender.sendMessage(Component.text(line, NamedTextColor.YELLOW));
        }
    }

    private OptionalLong parsePositiveLong(String input, CommandSender sender, String label) {
        long value;
        try {
            value = Long.parseLong(input);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("Invalid " + label.toLowerCase() + ": " + input, NamedTextColor.RED));
            return OptionalLong.empty();
        }
        if (value <= 0) {
            sender.sendMessage(Component.text(label + " must be greater than 0.", NamedTextColor.RED));
            return OptionalLong.empty();
        }
        return OptionalLong.of(value);
    }

    private int safeInt(long value, CommandSender sender, String label) {
        if (value > Integer.MAX_VALUE) {
            sender.sendMessage(Component.text(label + " is too large.", NamedTextColor.RED));
            return -1;
        }
        return (int) value;
    }

    private List<String> filter(List<String> values, String prefix) {
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                matches.add(value);
            }
        }
        return matches;
    }
}
