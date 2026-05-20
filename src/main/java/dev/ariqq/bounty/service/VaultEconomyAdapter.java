package dev.ariqq.bounty.service;

import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public final class VaultEconomyAdapter implements EconomyAdapter {
    private final Economy economy;

    public VaultEconomyAdapter(Economy economy) {
        this.economy = economy;
    }

    @Override
    public boolean has(UUID playerId, double amount) {
        return economy.has(resolvePlayer(playerId), amount);
    }

    @Override
    public boolean withdraw(UUID playerId, String playerName, double amount) {
        EconomyResponse response = economy.withdrawPlayer(resolvePlayer(playerId, playerName), amount);
        return response.transactionSuccess();
    }

    @Override
    public boolean deposit(UUID playerId, String playerName, double amount) {
        EconomyResponse response = economy.depositPlayer(resolvePlayer(playerId, playerName), amount);
        return response.transactionSuccess();
    }

    private OfflinePlayer resolvePlayer(UUID playerId) {
        return Bukkit.getOfflinePlayer(playerId);
    }

    private OfflinePlayer resolvePlayer(UUID playerId, String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        if (player.getName() != null) {
            return player;
        }
        return Bukkit.getOfflinePlayer(playerName);
    }
}
