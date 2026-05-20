package dev.ariqq.bounty.listener;

import dev.ariqq.bounty.service.ClaimResult;
import dev.ariqq.bounty.service.BountyService;
import dev.ariqq.bounty.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class BountyDeathListener implements Listener {
    private final BountyService bountyService;

    public BountyDeathListener(BountyService bountyService) {
        this.bountyService = bountyService;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player killer = event.getPlayer().getKiller();
        if (killer == null) {
            return;
        }
        ClaimResult result = bountyService.claimIfEligible(killer, event.getPlayer());
        if (result.success()) {
            killer.sendMessage(Msg.ok(result.message()));
            return;
        }
        if (!"No bounty.".equals(result.message())) {
            killer.sendMessage(Msg.err(result.message()));
        }
    }
}
