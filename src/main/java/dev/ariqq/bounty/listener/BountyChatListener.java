package dev.ariqq.bounty.listener;

import dev.ariqq.bounty.gui.BountyGuiManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class BountyChatListener implements Listener {
    private final BountyGuiManager guiManager;

    public BountyChatListener(BountyGuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (guiManager.handleChatInput(event.getPlayer(), event.message())) {
            event.setCancelled(true);
        }
    }
}
