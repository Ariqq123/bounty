package dev.ariqq.bounty.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BountyGuiListener implements Listener {
    private final BountyGuiManager guiManager;

    public BountyGuiListener(BountyGuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof BountyInventoryView)) {
            return;
        }
        event.setCancelled(true);
        guiManager.handleClick(player, event.getInventory(), event.getSlot());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        guiManager.clearPrompt(event.getPlayer().getUniqueId());
    }
}
