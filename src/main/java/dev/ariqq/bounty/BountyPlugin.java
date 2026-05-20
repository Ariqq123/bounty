package dev.ariqq.bounty;

import dev.ariqq.bounty.command.BountyCommand;
import dev.ariqq.bounty.config.BountyConfig;
import dev.ariqq.bounty.discord.BountyNotifier;
import dev.ariqq.bounty.discord.DiscordWebhookNotifier;
import dev.ariqq.bounty.gui.BountyGuiListener;
import dev.ariqq.bounty.gui.BountyGuiManager;
import dev.ariqq.bounty.listener.BountyChatListener;
import dev.ariqq.bounty.listener.BountyDeathListener;
import dev.ariqq.bounty.service.BountyService;
import dev.ariqq.bounty.service.EconomyAdapter;
import dev.ariqq.bounty.service.VaultEconomyAdapter;
import dev.ariqq.bounty.storage.BountyRepository;
import dev.ariqq.bounty.storage.MysqlBountyRepository;
import dev.ariqq.bounty.storage.SqliteBountyRepository;
import dev.ariqq.bounty.util.Msg;
import java.io.File;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class BountyPlugin extends JavaPlugin {
    private BountyConfig bountyConfig;
    private BountyRepository repository;
    private BountyService bountyService;
    private BountyGuiManager guiManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadBountyConfig();

        Economy economy = resolveEconomy();
        if (economy == null) {
            getLogger().severe("Vault economy provider not found. Disabling Bounty.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            if (bountyConfig.useMysqlStorage()) {
                repository = new MysqlBountyRepository(
                    bountyConfig.mysqlHost(),
                    bountyConfig.mysqlPort(),
                    bountyConfig.mysqlDatabase(),
                    bountyConfig.mysqlUsername(),
                    bountyConfig.mysqlPassword(),
                    bountyConfig.mysqlUseSsl()
                );
                getLogger().info("Using MySQL storage: " + bountyConfig.mysqlHost() + ":" + bountyConfig.mysqlPort() + "/" + bountyConfig.mysqlDatabase());
            } else {
                File dataFile = new File(getDataFolder(), "bounty.db");
                repository = new SqliteBountyRepository(dataFile.toPath());
                getLogger().info("Using SQLite storage: " + dataFile.getAbsolutePath());
            }
        } catch (SQLException exception) {
            getLogger().severe("Failed to initialize storage backend: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        EconomyAdapter economyAdapter = new VaultEconomyAdapter(economy);
        Supplier<BountyConfig> configSupplier = this::getBountyConfig;
        BountyNotifier bountyNotifier = new DiscordWebhookNotifier(configSupplier, getLogger());
        bountyService = new BountyService(this, getLogger(), repository, economyAdapter, bountyNotifier, configSupplier);
        guiManager = new BountyGuiManager(this, bountyService);

        PluginCommand command = getCommand("bounty");
        BountyCommand bountyCommand = new BountyCommand(this, bountyService, guiManager);
        Objects.requireNonNull(command, "bounty command").setExecutor(bountyCommand);
        command.setTabCompleter(bountyCommand);

        Bukkit.getPluginManager().registerEvents(new BountyDeathListener(bountyService), this);
        Bukkit.getPluginManager().registerEvents(new BountyChatListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(new BountyGuiListener(guiManager), this);
    }

    @Override
    public void onDisable() {
        if (repository != null) {
            repository.close();
        }
    }

    public void reloadBountyConfig() {
        reloadConfig();
        bountyConfig = BountyConfig.fromConfig(getConfig());
        Msg.configure(bountyConfig.messagePrefix(), bountyConfig.beautifyMessages());
    }

    public BountyConfig getBountyConfig() {
        return bountyConfig;
    }

    private Economy resolveEconomy() {
        RegisteredServiceProvider<Economy> registration =
            Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }
}
