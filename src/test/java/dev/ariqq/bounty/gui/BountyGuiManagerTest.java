package dev.ariqq.bounty.gui;

import dev.ariqq.bounty.model.KnownPlayer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BountyGuiManagerTest {
    @Test
    void clearPromptIfMatchesConsumesPromptOnlyOnce() {
        BountyGuiManager manager = new BountyGuiManager(null, null);
        UUID playerUuid = UUID.randomUUID();
        KnownPlayer target = new KnownPlayer(UUID.randomUUID(), "Target");
        Instant createdAt = Instant.parse("2026-05-20T00:00:00Z");

        manager.rememberPrompt(playerUuid, target, createdAt);

        boolean first = manager.clearPromptIfMatches(playerUuid, target, createdAt);
        boolean second = manager.clearPromptIfMatches(playerUuid, target, createdAt);

        Assertions.assertTrue(first);
        Assertions.assertFalse(second);
    }
}
