package dev.ariqq.bounty.service;

public record ClaimResult(
    boolean success,
    String message,
    long amountPaid,
    String targetName
) {
    public static ClaimResult success(String message, long amountPaid, String targetName) {
        return new ClaimResult(true, message, amountPaid, targetName);
    }

    public static ClaimResult failure(String message) {
        return new ClaimResult(false, message, 0L, null);
    }
}
