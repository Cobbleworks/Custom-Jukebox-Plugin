package dev.customjukebox.config;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(int minVolume, int maxVolume, int defaultVolume,
                             int personalVolume, int maxActiveSources,
                             int redstoneRadius, boolean logInvalidSongs) {
    /** Upper bound for the redstone search cube; larger values get expensive on busy redstone. */
    public static final int MAX_REDSTONE_RADIUS = 8;

    public static PluginSettings from(FileConfiguration config) {
        int min = Math.max(0, config.getInt("volume.min", 0));
        int max = Math.max(min, config.getInt("volume.max", 10));
        int def = clamp(config.getInt("volume.default", 3), min, max);
        int personal = clamp(config.getInt("personal-default-volume", def), min, max);
        return new PluginSettings(min, max, def, personal,
                Math.max(1, config.getInt("max-active-sources", 20)),
                clamp(config.getInt("redstone.trigger-radius", 2), 0, MAX_REDSTONE_RADIUS),
                config.getBoolean("log-invalid-songs", true));
    }

    public int clampVolume(int volume) {
        return clamp(volume, minVolume, maxVolume);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
