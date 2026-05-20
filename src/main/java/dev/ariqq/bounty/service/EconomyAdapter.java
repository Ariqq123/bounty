package dev.ariqq.bounty.service;

import java.util.UUID;

public interface EconomyAdapter {
    boolean has(UUID playerId, double amount);

    boolean withdraw(UUID playerId, String playerName, double amount);

    boolean deposit(UUID playerId, String playerName, double amount);
}
