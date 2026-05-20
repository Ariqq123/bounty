package dev.ariqq.bounty.model;

import java.time.Instant;
import java.util.UUID;

public record BountyClaim(
    long id,
    UUID targetUuid,
    String targetName,
    UUID killerUuid,
    String killerName,
    long totalAmount,
    int sourceCount,
    Instant claimedAt
) {
}
