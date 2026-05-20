package dev.ariqq.bounty.gui;

import dev.ariqq.bounty.BountyPlugin;
import dev.ariqq.bounty.model.BountyContribution;
import dev.ariqq.bounty.model.BountyTargetSummary;
import dev.ariqq.bounty.model.KnownPlayer;
import dev.ariqq.bounty.model.ServiceResult;
import dev.ariqq.bounty.service.BountyService;
import dev.ariqq.bounty.util.MoneyFormatter;
import dev.ariqq.bounty.util.Msg;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class BountyGuiManager {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final BountyPlugin plugin;
    private final BountyService bountyService;
    private final Map<UUID, PendingAmountPrompt> prompts = new ConcurrentHashMap<>();

    public BountyGuiManager(BountyPlugin plugin, BountyService bountyService) {
        this.plugin = plugin;
        this.bountyService = bountyService;
    }

    public void openMain(Player player) {
        BountyInventoryView holder = new BountyInventoryView(ViewType.MAIN, 1);
        Inventory inventory = Bukkit.createInventory(holder, 27,
            Component.text("☆ ", NamedTextColor.GOLD)
                .append(Component.text("Bounty Menu", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)));
        holder.setInventory(inventory);
        inventory.setItem(11, item(Material.PAPER, "Active Bounties", "Browse all active bounty targets.\nClick to open the list."));
        inventory.setItem(13, item(Material.GOLD_INGOT, "Top Bounties", "See the highest-value bounty pools.\nClick to view the leaderboard."));
        inventory.setItem(15, item(
            Material.PLAYER_HEAD,
            "My Bounties",
            hasCancelPermission(player)
                ? "See and cancel your active contributions."
                : "View your active contributions."
        ));
        inventory.setItem(22, item(
            Material.CROSSBOW,
            "Place Bounty",
            hasPlacePermission(player)
                ? "Pick a target and enter the amount in chat."
                : "You do not have permission to place bounties."
        ));
        player.openInventory(inventory);
    }

    public void openActiveList(Player player, int page) {
        BountyInventoryView holder = new BountyInventoryView(ViewType.ACTIVE_LIST, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
            Component.text("Active Bounties  —  Page " + page, NamedTextColor.GOLD));
        holder.setInventory(inventory);
        List<BountyTargetSummary> summaries = bountyService.listActiveTargets(page, bountyService.config().guiPageSize());
        int totalTargets = bountyService.countActiveTargets();
        fillSummaryItems(holder, inventory, summaries);
        if (summaries.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "No Active Bounties", "There are no targets on this page."));
        }
        applyNavigation(
            inventory,
            page,
            page > 1,
            hasNextPage(page, bountyService.config().guiPageSize(), totalTargets)
        );
        player.openInventory(inventory);
    }

    public void openTopList(Player player, int page) {
        BountyInventoryView holder = new BountyInventoryView(ViewType.TOP_LIST, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
            Component.text("Top Bounties  —  Page " + page, NamedTextColor.GOLD));
        holder.setInventory(inventory);
        List<BountyTargetSummary> summaries = bountyService.listActiveTargets(page, bountyService.config().guiPageSize());
        int totalTargets = bountyService.countActiveTargets();
        fillSummaryItems(holder, inventory, summaries);
        if (summaries.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "No Bounties Yet", "There are no active bounty targets."));
        }
        applyNavigation(
            inventory,
            page,
            page > 1,
            hasNextPage(page, bountyService.config().guiPageSize(), totalTargets)
        );
        player.openInventory(inventory);
    }

    public void openMyBounties(Player player) {
        openMyBounties(player, 1);
    }

    public void openMyBounties(Player player, int requestedPage) {
        List<BountyContribution> contributions = bountyService.getPlayerContributions(player.getUniqueId());
        int maxPage = Math.max(1, (int) Math.ceil(contributions.size() / 45.0D));
        int page = Math.max(1, Math.min(requestedPage, maxPage));
        long start = pageStart(page, 45);

        BountyInventoryView holder = new BountyInventoryView(ViewType.MY_BOUNTIES, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
            Component.text("My Bounties  —  Page " + page, NamedTextColor.GOLD));
        holder.setInventory(inventory);
        int slot = 0;
        for (BountyContribution contribution : contributions.stream().skip(start).limit(45).toList()) {
            holder.setTarget(slot, new KnownPlayer(contribution.targetUuid(), contribution.targetName()));
            inventory.setItem(slot++, item(
                Material.NAME_TAG,
                contribution.targetName() + " - " + MoneyFormatter.format(contribution.amount()),
                contribution.adminFunded()
                    ? "Admin-funded — cannot be cancelled."
                    :
                hasCancelPermission(player)
                    ? "Click to cancel  ▸  refunds " + bountyService.config().cancelRefundPercent() + "%."
                    : "No permission to cancel."
            ));
        }
        if (slot == 0) {
            inventory.setItem(22, item(Material.BARRIER, "Nothing Here", "You have no active bounty contributions."));
        }
        applyNavigation(inventory, page, page > 1, hasNextPage(page, 45, contributions.size()));
        player.openInventory(inventory);
    }

    public void openTargetSelect(Player player, int page) {
        BountyInventoryView holder = new BountyInventoryView(ViewType.TARGET_SELECT, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
            Component.text("Choose Target  —  Page " + page, NamedTextColor.GOLD));
        holder.setInventory(inventory);
        List<KnownPlayer> knownPlayers = bountyService.listKnownPlayers().stream()
            .filter(knownPlayer -> !knownPlayer.uuid().equals(player.getUniqueId()))
            .toList();
        long start = pageStart(page, 45);
        int slot = 0;
        for (KnownPlayer knownPlayer : knownPlayers.stream().skip(start).limit(45).toList()) {
            holder.setTarget(slot, knownPlayer);
            inventory.setItem(slot++, item(Material.PLAYER_HEAD, knownPlayer.name(), "Click to place a bounty on this player."));
        }
        if (slot == 0) {
            inventory.setItem(22, item(Material.BARRIER, "No Players Found", "No other known players are available."));
        }
        applyNavigation(inventory, page, page > 1, hasNextPage(page, 45, knownPlayers.size()));
        player.openInventory(inventory);
    }

    public void handleClick(Player player, Inventory inventory, int slot) {
        if (!(inventory.getHolder() instanceof BountyInventoryView holder)) {
            return;
        }
        ItemStack clicked = inventory.getItem(slot);
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String title = PLAIN.serialize(clicked.getItemMeta().displayName());
        switch (holder.viewType()) {
            case MAIN -> handleMainClick(player, title);
            case ACTIVE_LIST -> handlePagedClick(player, holder, slot, title, holder.page(), ViewType.ACTIVE_LIST);
            case TOP_LIST -> handlePagedClick(player, holder, slot, title, holder.page(), ViewType.TOP_LIST);
            case MY_BOUNTIES -> {
                if ("Next Page".equalsIgnoreCase(title)) {
                    openMyBounties(player, holder.page() + 1);
                    return;
                }
                if ("Previous Page".equalsIgnoreCase(title)) {
                    openMyBounties(player, Math.max(1, holder.page() - 1));
                    return;
                }
                if ("Back".equalsIgnoreCase(title)) {
                    openMain(player);
                } else {
                    if (!hasCancelPermission(player)) {
                        Msg.send(player, Msg.err("You do not have permission to cancel bounties."));
                        return;
                    }
                    KnownPlayer target = holder.getTarget(slot);
                    if (target == null) {
                        Msg.send(player, Msg.err("That bounty entry is no longer available."));
                        openMyBounties(player, holder.page());
                        return;
                    }
                    ServiceResult result = bountyService.cancelOwnBounty(player.getUniqueId(), player.getName(), target);
                    Msg.send(player, Msg.result(result.success(), result.message()));
                    openMyBounties(player, holder.page());
                }
            }
            case TARGET_SELECT -> {
                if ("Next Page".equalsIgnoreCase(title)) {
                    openTargetSelect(player, holder.page() + 1);
                    return;
                }
                if ("Previous Page".equalsIgnoreCase(title)) {
                    openTargetSelect(player, Math.max(1, holder.page() - 1));
                    return;
                }
                if ("Back".equalsIgnoreCase(title)) {
                    openMain(player);
                    return;
                }
                if (!hasPlacePermission(player)) {
                    Msg.send(player, Msg.err("You do not have permission to place bounties."));
                    openMain(player);
                    return;
                }
                KnownPlayer target = holder.getTarget(slot);
                if (target == null) {
                    Msg.send(player, Msg.err("That target is no longer available."));
                    openTargetSelect(player, holder.page());
                    return;
                }
                rememberPrompt(player.getUniqueId(), target, Instant.now());
                player.closeInventory();
                Msg.send(player, Msg.info("Type the bounty amount for " + target.name() + " in chat, or type cancel."));
            }
        }
    }

    public boolean handleChatInput(Player player, Component message) {
        PendingAmountPrompt prompt = prompts.get(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        if (Duration.between(prompt.createdAt(), Instant.now()).toMinutes() >= 3) {
            prompts.remove(player.getUniqueId());
            sendPromptMessage(player, Msg.err("Bounty input expired. Please try again."));
            return true;
        }

        String plain = PLAIN.serialize(message).trim();
        if (plain.equalsIgnoreCase("cancel")) {
            prompts.remove(player.getUniqueId());
            sendPromptMessage(player, Msg.muted("Bounty placement cancelled."));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(plain);
        } catch (NumberFormatException exception) {
            sendPromptMessage(player, Msg.err("Please enter a whole number, or type cancel."));
            return true;
        }

        if (!clearPromptIfMatches(player.getUniqueId(), prompt.target(), prompt.createdAt())) {
            return true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.isValid()) {
                return;
            }
            if (!hasPlacePermission(player)) {
                Msg.send(player, Msg.err("You do not have permission to place bounties."));
                return;
            }
            ServiceResult result = bountyService.placeBounty(player.getUniqueId(), player.getName(), prompt.target(), amount);
            Msg.send(player, Msg.result(result.success(), result.message()));
        });
        return true;
    }

    public void clearPrompt(UUID playerUuid) {
        prompts.remove(playerUuid);
    }

    void rememberPrompt(UUID playerUuid, KnownPlayer target, Instant createdAt) {
        prompts.put(playerUuid, new PendingAmountPrompt(target, createdAt));
    }

    boolean clearPromptIfMatches(UUID playerUuid, KnownPlayer target, Instant createdAt) {
        return prompts.remove(playerUuid, new PendingAmountPrompt(target, createdAt));
    }

    private void handleMainClick(Player player, String title) {
        switch (title.toLowerCase()) {
            case "active bounties" -> openActiveList(player, 1);
            case "top bounties" -> openTopList(player, 1);
            case "my bounties" -> openMyBounties(player);
            case "place bounty" -> {
                if (!hasPlacePermission(player)) {
                    Msg.send(player, Msg.err("You do not have permission to place bounties."));
                    return;
                }
                openTargetSelect(player, 1);
            }
            default -> {
            }
        }
    }

    private void handlePagedClick(Player player, BountyInventoryView holder, int slot, String title, int page, ViewType viewType) {
        if ("Next Page".equalsIgnoreCase(title)) {
            if (viewType == ViewType.TOP_LIST) {
                openTopList(player, page + 1);
            } else {
                openActiveList(player, page + 1);
            }
            return;
        }
        if ("Previous Page".equalsIgnoreCase(title)) {
            if (viewType == ViewType.TOP_LIST) {
                openTopList(player, Math.max(1, page - 1));
            } else {
                openActiveList(player, Math.max(1, page - 1));
            }
            return;
        }
        if ("Back".equalsIgnoreCase(title)) {
            openMain(player);
            return;
        }
        KnownPlayer target = holder.getTarget(slot);
        if (target != null) {
            bountyService.sendInfo(player, target);
        }
    }

    private void fillSummaryItems(BountyInventoryView holder, Inventory inventory, List<BountyTargetSummary> summaries) {
        int slot = 0;
        for (BountyTargetSummary summary : summaries.stream().limit(45).toList()) {
            holder.setTarget(slot, new KnownPlayer(summary.targetUuid(), summary.targetName()));
            inventory.setItem(slot++, item(
                Material.PLAYER_HEAD,
                summary.targetName() + "  —  " + MoneyFormatter.format(summary.totalAmount()),
                summary.contributorCount() + " contributor(s). Click for details."
            ));
        }
    }

    private void applyNavigation(Inventory inventory, int page, boolean hasPrevious, boolean hasNext) {
        if (hasPrevious) {
            inventory.setItem(45, item(Material.ARROW, "◀ Previous Page", "Go to page " + Math.max(1, page - 1) + "."));
        }
        inventory.setItem(49, item(Material.BARRIER, "✕ Close", "Return to the main menu."));
        if (hasNext) {
            inventory.setItem(53, item(Material.ARROW, "Next Page ▶", "Go to page " + (page + 1) + "."));
        }
    }

    private ItemStack item(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(loreLine, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private void sendPromptMessage(Player player, Component message) {
        if (Bukkit.isPrimaryThread()) {
            Msg.send(player, message);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isValid()) {
                Msg.send(player, message);
            }
        });
    }

    private boolean hasPlacePermission(Player player) {
        return player.hasPermission("bounty.place");
    }

    private boolean hasCancelPermission(Player player) {
        return player.hasPermission("bounty.cancel.own");
    }

    private boolean hasNextPage(int page, int pageSize, int totalItems) {
        return ((long) page * pageSize) < totalItems;
    }

    private long pageStart(int page, int pageSize) {
        return Math.max(0L, (long) (page - 1) * pageSize);
    }

    private record PendingAmountPrompt(KnownPlayer target, Instant createdAt) {
    }
}
