package dev.ariqq.bounty.gui;

import dev.ariqq.bounty.BountyPlugin;
import dev.ariqq.bounty.model.BountyContribution;
import dev.ariqq.bounty.model.BountyTargetSummary;
import dev.ariqq.bounty.model.KnownPlayer;
import dev.ariqq.bounty.model.ServiceResult;
import dev.ariqq.bounty.service.BountyService;
import dev.ariqq.bounty.util.MoneyFormatter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Bounty Menu"));
        holder.setInventory(inventory);
        inventory.setItem(11, item(Material.PAPER, "Active Bounties", "Browse all active bounty targets."));
        inventory.setItem(13, item(Material.GOLD_INGOT, "Top Bounties", "See the highest value bounty pools."));
        inventory.setItem(15, item(
            Material.PLAYER_HEAD,
            "My Bounties",
            hasCancelPermission(player)
                ? "See and manage your active contributions."
                : "See your active contributions."
        ));
        inventory.setItem(22, item(
            Material.CROSSBOW,
            "Place Bounty",
            hasPlacePermission(player)
                ? "Pick a target and enter amount in chat."
                : "You do not have permission to place bounties."
        ));
        player.openInventory(inventory);
    }

    public void openActiveList(Player player, int page) {
        BountyInventoryView holder = new BountyInventoryView(ViewType.ACTIVE_LIST, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Active Bounties"));
        holder.setInventory(inventory);
        List<BountyTargetSummary> summaries = bountyService.listActiveTargets(page, bountyService.config().guiPageSize());
        int totalTargets = bountyService.countActiveTargets();
        fillSummaryItems(inventory, summaries);
        if (summaries.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "No Active Bounties", "There are no bounty targets on this page."));
        }
        applyNavigation(
            inventory,
            page,
            page > 1,
            page * bountyService.config().guiPageSize() < totalTargets
        );
        player.openInventory(inventory);
    }

    public void openTopList(Player player, int page) {
        BountyInventoryView holder = new BountyInventoryView(ViewType.TOP_LIST, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Top Bounties"));
        holder.setInventory(inventory);
        List<BountyTargetSummary> summaries = bountyService.listActiveTargets(page, bountyService.config().guiPageSize());
        int totalTargets = bountyService.countActiveTargets();
        fillSummaryItems(inventory, summaries);
        if (summaries.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "No Top Bounties", "There are no bounty targets on this page."));
        }
        applyNavigation(
            inventory,
            page,
            page > 1,
            page * bountyService.config().guiPageSize() < totalTargets
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
        int start = (page - 1) * 45;

        BountyInventoryView holder = new BountyInventoryView(ViewType.MY_BOUNTIES, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("My Bounties"));
        holder.setInventory(inventory);
        int slot = 0;
        for (BountyContribution contribution : contributions.stream().skip(start).limit(45).toList()) {
            inventory.setItem(slot++, item(
                Material.NAME_TAG,
                contribution.targetName() + " - " + MoneyFormatter.format(contribution.amount()),
                hasCancelPermission(player)
                    ? "Click to cancel and refund " + bountyService.config().cancelRefundPercent() + " percent."
                    : "You do not have permission to cancel your bounty."
            ));
        }
        if (slot == 0) {
            inventory.setItem(22, item(Material.BARRIER, "No Active Bounties", "You do not have active bounty contributions on this page."));
        }
        applyNavigation(inventory, page, page > 1, start + 45 < contributions.size());
        player.openInventory(inventory);
    }

    public void openTargetSelect(Player player, int page) {
        BountyInventoryView holder = new BountyInventoryView(ViewType.TARGET_SELECT, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Choose Target"));
        holder.setInventory(inventory);
        List<KnownPlayer> knownPlayers = bountyService.listKnownPlayers().stream()
            .filter(knownPlayer -> !knownPlayer.uuid().equals(player.getUniqueId()))
            .toList();
        int start = Math.max(0, (page - 1) * 45);
        int slot = 0;
        for (KnownPlayer knownPlayer : knownPlayers.stream().skip(start).limit(45).toList()) {
            inventory.setItem(slot++, item(Material.PLAYER_HEAD, knownPlayer.name(), "Click to enter a bounty amount in chat."));
        }
        if (slot == 0) {
            inventory.setItem(22, item(Material.BARRIER, "No Available Targets", "No known players are available on this page."));
        }
        applyNavigation(inventory, page, page > 1, start + 45 < knownPlayers.size());
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
            case ACTIVE_LIST -> handlePagedClick(player, title, holder.page(), ViewType.ACTIVE_LIST);
            case TOP_LIST -> handlePagedClick(player, title, holder.page(), ViewType.TOP_LIST);
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
                        player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
                        return;
                    }
                    String targetName = title.split(" - ")[0];
                    bountyService.resolveKnownPlayer(targetName).ifPresent(target -> {
                        ServiceResult result = bountyService.cancelOwnBounty(player.getUniqueId(), player.getName(), target);
                        player.sendMessage(colored(result));
                        openMyBounties(player, holder.page());
                    });
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
                    player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
                    openMain(player);
                    return;
                }
                bountyService.resolveKnownPlayer(title).ifPresent(target -> {
                    prompts.put(player.getUniqueId(), new PendingAmountPrompt(target, Instant.now()));
                    player.closeInventory();
                    player.sendMessage(Component.text(
                        "Type the bounty amount for " + target.name() + " in chat, or type cancel.",
                        NamedTextColor.YELLOW
                    ));
                });
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
            player.sendMessage(Component.text("Bounty input expired.", NamedTextColor.RED));
            return true;
        }

        String plain = PLAIN.serialize(message).trim();
        if (plain.equalsIgnoreCase("cancel")) {
            prompts.remove(player.getUniqueId());
            player.sendMessage(Component.text("Bounty placement cancelled.", NamedTextColor.RED));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(plain);
        } catch (NumberFormatException exception) {
            player.sendMessage(Component.text("Please enter a whole number, or type cancel.", NamedTextColor.RED));
            return true;
        }

        prompts.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            ServiceResult result = bountyService.placeBounty(player.getUniqueId(), player.getName(), prompt.target(), amount);
            player.sendMessage(colored(result));
        });
        return true;
    }

    public void clearPrompt(UUID playerUuid) {
        prompts.remove(playerUuid);
    }

    private void handleMainClick(Player player, String title) {
        switch (title.toLowerCase()) {
            case "active bounties" -> openActiveList(player, 1);
            case "top bounties" -> openTopList(player, 1);
            case "my bounties" -> openMyBounties(player);
            case "place bounty" -> {
                if (!hasPlacePermission(player)) {
                    player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
                    return;
                }
                openTargetSelect(player, 1);
            }
            default -> {
            }
        }
    }

    private void handlePagedClick(Player player, String title, int page, ViewType viewType) {
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
        bountyService.resolveKnownPlayer(title.split(" - ")[0]).ifPresent(target -> bountyService.sendInfo(player, target));
    }

    private void fillSummaryItems(Inventory inventory, List<BountyTargetSummary> summaries) {
        int slot = 0;
        for (BountyTargetSummary summary : summaries.stream().limit(45).toList()) {
            inventory.setItem(slot++, item(
                Material.PLAYER_HEAD,
                summary.targetName() + " - " + MoneyFormatter.format(summary.totalAmount()),
                summary.contributorCount() + " contributors. Click for details."
            ));
        }
    }

    private void applyNavigation(Inventory inventory, int page, boolean hasPrevious, boolean hasNext) {
        if (hasPrevious) {
            inventory.setItem(45, item(Material.ARROW, "Previous Page", "Go to page " + Math.max(1, page - 1) + "."));
        }
        inventory.setItem(49, item(Material.BARRIER, "Back", "Return to the main menu."));
        if (hasNext) {
            inventory.setItem(53, item(Material.ARROW, "Next Page", "Go to page " + (page + 1) + "."));
        }
    }

    private ItemStack item(Material material, String name, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD));
        meta.lore(List.of(Component.text(loreLine, NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private Component colored(ServiceResult result) {
        return Component.text(result.message(), result.success() ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private boolean hasPlacePermission(Player player) {
        return player.hasPermission("bounty.place");
    }

    private boolean hasCancelPermission(Player player) {
        return player.hasPermission("bounty.cancel.own");
    }

    private record PendingAmountPrompt(KnownPlayer target, Instant createdAt) {
    }
}
