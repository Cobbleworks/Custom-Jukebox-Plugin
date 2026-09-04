package dev.customjukebox.disc;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomDiscManagerTest {
    @Test
    void iconPoolContainsOnlyIntactMusicDiscs() {
        assertFalse(CustomDiscManager.discIcons().isEmpty());
        assertFalse(CustomDiscManager.discIcons().contains(Material.MUSIC_DISC_11));
        assertTrue(CustomDiscManager.discIcons().stream()
                .allMatch(material -> material.name().startsWith("MUSIC_DISC_")));
        assertTrue(CustomDiscManager.discIcons().stream()
                .noneMatch(material -> material.name().startsWith("DISC_FRAGMENT_")));
    }
}
