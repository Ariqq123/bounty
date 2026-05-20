package dev.ariqq.bounty.gui;

import dev.ariqq.bounty.model.KnownPlayer;
import java.util.HashMap;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class BountyInventoryView implements InventoryHolder {
    private final ViewType viewType;
    private final int page;
    private final HashMap<Integer, KnownPlayer> slotTargets = new HashMap<>();
    private Inventory inventory;

    public BountyInventoryView(ViewType viewType, int page) {
        this.viewType = viewType;
        this.page = page;
    }

    public ViewType viewType() {
        return viewType;
    }

    public int page() {
        return page;
    }

    public void setTarget(int slot, KnownPlayer target) {
        slotTargets.put(slot, target);
    }

    public KnownPlayer getTarget(int slot) {
        return slotTargets.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
