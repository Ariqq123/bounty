package dev.ariqq.bounty.gui;

import dev.ariqq.bounty.model.KnownPlayer;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BountyInventoryViewTest {
    @Test
    void storesTargetBySlot() {
        BountyInventoryView view = new BountyInventoryView(ViewType.ACTIVE_LIST, 2);
        KnownPlayer target = new KnownPlayer(UUID.randomUUID(), "Target");

        view.setTarget(7, target);

        Assertions.assertEquals(target, view.getTarget(7));
    }

    @Test
    void missingSlotReturnsNull() {
        BountyInventoryView view = new BountyInventoryView(ViewType.TOP_LIST, 1);

        Assertions.assertNull(view.getTarget(3));
    }
}
