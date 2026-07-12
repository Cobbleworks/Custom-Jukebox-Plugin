package dev.customjukebox.sign;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.song.SongMetadata;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SignManager {
    private final CustomJukeboxPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey songKey;
    private final NamespacedKey volumeKey;
    private final NamespacedKey loopKey;
    private final NamespacedKey redstoneKey;
    private final File indexFile;
    private final Set<BlockKey> index = new LinkedHashSet<>();
    private final Map<BlockKey, Boolean> powered = new HashMap<>();

    public SignManager(CustomJukeboxPlugin plugin) {
        this.plugin = plugin;
        markerKey = key("jukebox"); songKey = key("song"); volumeKey = key("volume");
        loopKey = key("loop"); redstoneKey = key("redstone");
        indexFile = new File(plugin.getDataFolder(), "signs.yml");
        loadIndex();
    }

    private NamespacedKey key(String value) { return new NamespacedKey(plugin, value); }

    public Optional<SignConfig> read(Sign sign) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        if (!pdc.has(markerKey, PersistentDataType.BYTE)) return Optional.empty();
        String song = pdc.getOrDefault(songKey, PersistentDataType.STRING, "");
        int volume = plugin.settings().clampVolume(pdc.getOrDefault(volumeKey, PersistentDataType.INTEGER,
                plugin.settings().defaultVolume()));
        boolean loop = pdc.getOrDefault(loopKey, PersistentDataType.BYTE, (byte) 0) != 0;
        String modeName = pdc.getOrDefault(redstoneKey, PersistentDataType.STRING, RedstoneMode.IGNORE.name());
        RedstoneMode mode;
        try { mode = RedstoneMode.valueOf(modeName); }
        catch (IllegalArgumentException ignored) { mode = RedstoneMode.IGNORE; }
        return Optional.of(new SignConfig(song, volume, loop, mode));
    }

    public void write(Sign sign, SignConfig config, boolean rewriteText) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(songKey, PersistentDataType.STRING, config.songId() == null ? "" : config.songId());
        pdc.set(volumeKey, PersistentDataType.INTEGER, plugin.settings().clampVolume(config.volume()));
        pdc.set(loopKey, PersistentDataType.BYTE, config.loop() ? (byte) 1 : (byte) 0);
        pdc.set(redstoneKey, PersistentDataType.STRING, config.redstoneMode().name());
        if (rewriteText) {
            sign.getSide(Side.FRONT).line(0, Component.text("[jukebox]"));
            String title = plugin.library().find(config.songId())
                    .map(SongMetadata::displayTitle)
                    .orElse(config.songId() == null ? "" : config.songId());
            sign.getSide(Side.FRONT).line(1, Component.text(truncate(title, 15)));
            sign.getSide(Side.FRONT).line(2, Component.empty());
        }
        sign.update(true, false);
        index.add(BlockKey.of(sign.getLocation()));
        saveIndex();
    }

    public void remove(Location location) {
        BlockKey key = BlockKey.of(location);
        plugin.playback().stop(key.sourceKey());
        index.remove(key);
        powered.remove(key);
        saveIndex();
    }

    public boolean play(Sign sign) {
        Optional<SignConfig> maybeConfig = read(sign);
        if (maybeConfig.isEmpty()) return false;
        SignConfig config = maybeConfig.get();
        Optional<SongMetadata> song = plugin.library().find(config.songId());
        if (song.isEmpty()) return false;
        BlockKey key = BlockKey.of(sign.getLocation());
        return plugin.playback().playSign(key.sourceKey(), sign.getLocation().toCenterLocation(), song.get(),
                config.volume(), config.loop(), () -> { });
    }

    public void stop(Sign sign) { plugin.playback().stop(BlockKey.of(sign.getLocation()).sourceKey()); }
    public boolean isPlaying(Sign sign) { return plugin.playback().isActive(BlockKey.of(sign.getLocation()).sourceKey()); }

    public void handlePower(Sign sign) {
        SignConfig config = read(sign).orElse(null);
        if (config == null || config.redstoneMode() == RedstoneMode.IGNORE) return;
        BlockKey key = BlockKey.of(sign.getLocation());
        boolean now = sign.getBlock().isBlockPowered() || sign.getBlock().isBlockIndirectlyPowered();
        boolean before = powered.getOrDefault(key, false);
        powered.put(key, now);
        if (config.redstoneMode() == RedstoneMode.TOGGLE) {
            if (now && !isPlaying(sign)) play(sign);
            else if (!now) stop(sign);
        } else if (config.redstoneMode() == RedstoneMode.PULSE && now && !before) {
            play(sign);
        }
    }

    public List<BlockKey> validateAndList() {
        boolean changed = index.removeIf(key -> {
            World world = plugin.getServer().getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return false;
            return !(world.getBlockAt(key.x(), key.y(), key.z()).getState() instanceof Sign sign) || read(sign).isEmpty();
        });
        if (changed) saveIndex();
        return new ArrayList<>(index);
    }

    public void activateLoadedSigns() {
        for (BlockKey key : validateAndList()) {
            World world = plugin.getServer().getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) continue;
            if (world.getBlockAt(key.x(), key.y(), key.z()).getState() instanceof Sign sign) handlePower(sign);
        }
    }

    private void loadIndex() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(indexFile);
        for (String encoded : yaml.getStringList("signs")) {
            BlockKey.parse(encoded).ifPresent(index::add);
        }
    }

    private void saveIndex() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("signs", index.stream().map(BlockKey::encode).toList());
        try { yaml.save(indexFile); }
        catch (IOException exception) { plugin.getLogger().warning("Could not save signs.yml: " + exception.getMessage()); }
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }

    public record BlockKey(String world, int x, int y, int z) {
        public static BlockKey of(Location location) {
            return new BlockKey(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
        public String sourceKey() { return "sign:" + encode(); }
        public String encode() { return world + ";" + x + ";" + y + ";" + z; }
        public static Optional<BlockKey> parse(String encoded) {
            String[] parts = encoded.split(";", -1);
            if (parts.length != 4) return Optional.empty();
            try { return Optional.of(new BlockKey(parts[0], Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))); }
            catch (NumberFormatException ignored) { return Optional.empty(); }
        }
    }
}
