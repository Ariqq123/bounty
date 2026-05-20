package dev.ariqq.bounty.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Msg {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static String prefixText = "&7[Bounty] ";
    private static boolean beautify = true;

    private Msg() {
    }

    public static void configure(String prefix, boolean enableBeautify) {
        prefixText = (prefix == null || prefix.isBlank()) ? "&7[Bounty] " : prefix;
        beautify = enableBeautify;
    }

    public static Component ok(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GREEN));
    }

    public static Component err(String text) {
        return prefix().append(Component.text(text, NamedTextColor.RED));
    }

    public static Component info(String text) {
        return prefix().append(Component.text(text, NamedTextColor.YELLOW));
    }

    public static Component muted(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GRAY));
    }

    public static Component header(String text) {
        if (!beautify) {
            return legacy("&6=== " + text + " ===");
        }
        return Component.text("━━━ ", NamedTextColor.DARK_GRAY)
            .append(Component.text(text, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text(" ━━━", NamedTextColor.DARK_GRAY));
    }

    public static Component hint(String command, String description) {
        if (!beautify) {
            return legacy("&7  " + command + " - " + description);
        }
        return Component.text("  " + command, NamedTextColor.AQUA)
            .append(Component.text("  —  " + description, NamedTextColor.DARK_GRAY));
    }

    public static Component entry(String text) {
        if (!beautify) {
            return legacy("&e - " + text);
        }
        return Component.text(" ✦ ", NamedTextColor.GOLD)
            .append(Component.text(text, NamedTextColor.YELLOW));
    }

    public static Component numbered(int n, String text) {
        if (!beautify) {
            return legacy("&6 " + n + ". &e" + text);
        }
        return Component.text(" " + n + ". ", NamedTextColor.GOLD)
            .append(Component.text(text, NamedTextColor.YELLOW));
    }

    public static Component result(boolean success, String text) {
        return success ? ok(text) : err(text);
    }

    public static Component legacy(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    public static String toLegacy(Component component) {
        return LEGACY.serialize(component == null ? Component.empty() : component);
    }

    public static void send(CommandSender sender, Component component) {
        if (sender == null) {
            return;
        }
        try {
            sender.sendMessage(component);
        } catch (NoSuchMethodError | AbstractMethodError exception) {
            sender.sendMessage(toLegacy(component));
        }
    }

    public static void send(Player player, Component component) {
        send((CommandSender) player, component);
    }

    public static void broadcast(Component component) {
        try {
            Bukkit.broadcast(component);
        } catch (NoSuchMethodError | AbstractMethodError exception) {
            Bukkit.broadcastMessage(toLegacy(component));
        }
    }

    private static Component prefix() {
        if (!beautify) {
            return legacy(prefixText);
        }
        String plainPrefix = toLegacy(legacy(prefixText)).strip();
        return Component.text("☆ ", NamedTextColor.GOLD)
            .append(Component.text(plainPrefix, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
            .append(Component.text(" ▸ ", NamedTextColor.DARK_GRAY));
    }
}
