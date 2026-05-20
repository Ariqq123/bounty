package dev.ariqq.bounty.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class Msg {

    private static String prefixText = "[Bounty] ";
    private static boolean beautify = true;

    private Msg() {}

    /** Configure message styling. Call on plugin enable/reload. */
    public static void configure(String prefix, boolean enableBeautify) {
        prefixText = prefix == null || prefix.isEmpty() ? "[Bounty] " : prefix;
        beautify = enableBeautify;
    }

    /** Prefixed success message (green). */
    public static Component ok(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GREEN));
    }

    /** Prefixed error message (red). */
    public static Component err(String text) {
        return prefix().append(Component.text(text, NamedTextColor.RED));
    }

    /** Prefixed informational message (yellow). */
    public static Component info(String text) {
        return prefix().append(Component.text(text, NamedTextColor.YELLOW));
    }

    /** Prefixed muted/secondary line (gray). */
    public static Component muted(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GRAY));
    }

    /** Section header (gold, bold, no prefix). */
    public static Component header(String text) {
        if (!beautify) {
            return Component.text("=== " + text + " ===", NamedTextColor.GOLD);
        }
        return Component.text("━━━ ", NamedTextColor.DARK_GRAY)
            .append(Component.text(text, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text(" ━━━", NamedTextColor.DARK_GRAY));
    }

    /** Command hint line (aqua key, gray description). */
    public static Component hint(String command, String description) {
        if (!beautify) {
            return Component.text("  " + command + " - " + description, NamedTextColor.GRAY);
        }
        return Component.text("  " + command, NamedTextColor.AQUA)
            .append(Component.text("  —  " + description, NamedTextColor.DARK_GRAY));
    }

    /** Bulleted list entry (gold bullet, yellow text). */
    public static Component entry(String text) {
        if (!beautify) {
            return Component.text(" - " + text, NamedTextColor.YELLOW);
        }
        return Component.text(" ✦ ", NamedTextColor.GOLD)
            .append(Component.text(text, NamedTextColor.YELLOW));
    }

    /** Numbered entry (gold number, yellow text). */
    public static Component numbered(int n, String text) {
        return Component.text(" " + n + ". ", NamedTextColor.GOLD)
            .append(Component.text(text, NamedTextColor.YELLOW));
    }

    /** Result message — green on success, red on failure, with prefix. */
    public static Component result(boolean success, String text) {
        return success ? ok(text) : err(text);
    }

    private static Component prefix() {
        if (!beautify) {
            return Component.text(prefixText, NamedTextColor.GRAY);
        }
        return Component.text("☆ ", NamedTextColor.GOLD)
            .append(Component.text(prefixText.strip(), NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
            .append(Component.text(" ▸ ", NamedTextColor.DARK_GRAY));
    }
}
