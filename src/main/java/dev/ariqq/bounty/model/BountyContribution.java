package dev.ariqq.bounty.model;

import java.time.Instant;
import java.util.UUID;

public record BountyContribution(
    long id,
    UUID targetUuid,
    String targetName,
    UUID placerUuid,
    String placerName,
    long amount,
    boolean adminFunded,
    ContributionStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}
