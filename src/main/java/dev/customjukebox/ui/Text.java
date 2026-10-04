package dev.customjukebox.ui;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.time.Duration;

/**
 * Shared chat styling so every plugin message carries the same prefix and colour scheme.
 */
public final class Text {
    private static final Component PREFIX = Component.text()
            .append(Component.text("♪ ", NamedTextColor.GOLD))
            .append(Component.text("Jukebox", NamedTextColor.YELLOW))
            .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
            .build();

    private Text() { }

    public static void info(Audience to, String message) { send(to, NamedTextColor.GRAY, message, null); }
    public static void info(Audience to, String message, String highlight) { send(to, NamedTextColor.GRAY, message, highlight); }
    public static void success(Audience to, String message) { send(to, NamedTextColor.GREEN, message, null); }
    public static void success(Audience to, String message, String highlight) { send(to, NamedTextColor.GREEN, message, highlight); }
    public static void error(Audience to, String message) { send(to, NamedTextColor.RED, message, null); }
    public static void error(Audience to, String message, String highlight) { send(to, NamedTextColor.RED, message, highlight); }

    public static void send(Audience to, Component body) {
        to.sendMessage(PREFIX.append(body));
    }

    private static void send(Audience to, TextColor color, String message, String highlight) {
        Component body = Component.text(message, color);
        if (highlight != null) body = body.append(Component.text(highlight, NamedTextColor.GOLD));
        send(to, body);
    }

    /** Plain, non-italic text for item names and lore. */
    public static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public static String duration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /** Renders a fixed-width bar such as {@code ■■■□□□} where filled cells represent {@code fraction}. */
    public static Component bar(double fraction, int cells, TextColor filled) {
        int full = (int) Math.round(Math.max(0, Math.min(1, fraction)) * cells);
        return Component.text()
                .append(Component.text("■".repeat(full), filled))
                .append(Component.text("■".repeat(cells - full), NamedTextColor.DARK_GRAY))
                .decoration(TextDecoration.ITALIC, false)
                .build();
    }
}
