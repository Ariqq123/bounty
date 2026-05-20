package dev.ariqq.bounty.model;

public record ServiceResult(boolean success, String message) {
    public static ServiceResult success(String message) {
        return new ServiceResult(true, message);
    }

    public static ServiceResult failure(String message) {
        return new ServiceResult(false, message);
    }
}
