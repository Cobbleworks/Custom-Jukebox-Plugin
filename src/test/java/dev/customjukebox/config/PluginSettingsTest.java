package dev.customjukebox.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginSettingsTest {
    @Test void clampsConfiguredDefaultsAndInputs() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("volume.min", 1);
        yaml.set("volume.max", 5);
        yaml.set("volume.default", 99);
        yaml.set("personal-default-volume", -5);
        PluginSettings settings = PluginSettings.from(yaml);
        assertEquals(5, settings.defaultVolume());
        assertEquals(1, settings.personalVolume());
        assertEquals(1, settings.clampVolume(-10));
        assertEquals(5, settings.clampVolume(10));
    }

    @Test void defaultsAndClampsRedstoneRadius() {
        assertEquals(2, PluginSettings.from(new YamlConfiguration()).redstoneRadius());

        YamlConfiguration negative = new YamlConfiguration();
        negative.set("redstone.trigger-radius", -3);
        assertEquals(0, PluginSettings.from(negative).redstoneRadius());

        YamlConfiguration huge = new YamlConfiguration();
        huge.set("redstone.trigger-radius", 100);
        assertEquals(PluginSettings.MAX_REDSTONE_RADIUS, PluginSettings.from(huge).redstoneRadius());
    }
}
