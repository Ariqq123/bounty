package dev.ariqq.bounty.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class BountyInventoryView implements InventoryHolder {
    private final ViewType viewType;
    private final int page;
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

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
