package dev.ariqq.bounty.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class Msg {

    // Brand prefix shown before every plugin message
    public static final Component PREFIX =
        Component.text("☆ ", NamedTextColor.GOLD)
            .append(Component.text("Bounty", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
            .append(Component.text(" ▸ ", NamedTextColor.DARK_GRAY));

    private Msg() {}

    /** Prefixed success message (green). */
    public static Component ok(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GREEN));
    }

    /** Prefixed error message (red). */
    public static Component err(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.RED));
    }

    /** Prefixed informational message (yellow). */
    public static Component info(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.YELLOW));
    }

    /** Prefixed muted/secondary line (gray). */
    public static Component muted(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GRAY));
    }

    /** Section header (gold, bold, no prefix). */
    public static Component header(String text) {
        return Component.text("━━━ ", NamedTextColor.DARK_GRAY)
            .append(Component.text(text, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
            .append(Component.text(" ━━━", NamedTextColor.DARK_GRAY));
    }

    /** Command hint line (aqua key, gray description). */
    public static Component hint(String command, String description) {
        return Component.text("  " + command, NamedTextColor.AQUA)
            .append(Component.text("  —  " + description, NamedTextColor.DARK_GRAY));
    }

    /** Bulleted list entry (gold bullet, yellow text). */
    public static Component entry(String text) {
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
}
