package dev.ariqq.bounty.model;

import java.util.UUID;

public record BountyTargetSummary(
    UUID targetUuid,
    String targetName,
    long totalAmount,
    int contributorCount
) {
}
