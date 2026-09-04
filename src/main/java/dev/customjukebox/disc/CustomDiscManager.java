package dev.customjukebox.disc;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.song.SongMetadata;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates song-bound records and keeps physical jukebox playback synchronized
 * with the record stored in each block.
 */
public final class CustomDiscManager implements Listener {
    private static final List<Material> DISC_ICONS = Arrays.stream(Material.values())
            .filter(material -> material.name().startsWith("MUSIC_DISC_"))
            .filter(material -> material != Material.MUSIC_DISC_11)
            .sorted(Comparator.comparing(Enum::name))
            .toList();

    private final CustomJukeboxPlugin plugin;
    private final NamespacedKey markerKey;
    private final NamespacedKey songKey;
    private final Set<BlockPosition> activeJukeboxes = ConcurrentHashMap.newKeySet();

    public CustomDiscManager(CustomJukeboxPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "custom_disc");
        this.songKey = new NamespacedKey(plugin, "custom_disc_song");
    }

    public ItemStack createDisc(SongMetadata song, int amount) {
        Material icon = DISC_ICONS.get(ThreadLocalRandom.current().nextInt(DISC_ICONS.size()));
        ItemStack disc = ItemStack.of(icon, Math.max(1, Math.min(amount, icon.getMaxStackSize())));
        ItemMeta meta = disc.getItemMeta();
        meta.displayName(Component.text(song.displayTitle(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(song.author().isBlank() ? "Custom Jukebox record" : song.author(), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(song.id(), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        data.set(songKey, PersistentDataType.STRING, song.id());
        disc.setItemMeta(meta);
        return disc;
    }

    public boolean isCustomDisc(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    public Optional<String> songId(ItemStack item) {
        if (!isCustomDisc(item)) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer()
                .get(songKey, PersistentDataType.STRING));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJukeboxInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.JUKEBOX) return;
        if (!(event.getClickedBlock().getState() instanceof Jukebox jukebox)) return;

        ItemStack record = jukebox.getRecord();
        if (isCustomDisc(record)) {
            event.setCancelled(true);
            stop(jukebox.getLocation());
            jukebox.eject();
            return;
        }

        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (jukebox.hasRecord() || !isCustomDisc(held)) return;
        if (!event.getPlayer().hasPermission("customjukebox.disc.use")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("You do not have permission to use custom records.");
            return;
        }

        event.setCancelled(true);
        ItemStack inserted = held.clone();
        inserted.setAmount(1);
        jukebox.setRecord(inserted);
        jukebox.update(true, false);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) held.subtract(1);
        start(jukebox, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJukeboxBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.JUKEBOX) stop(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Jukebox source = jukeboxHolder(event.getSource());
        Jukebox destination = jukeboxHolder(event.getDestination());
        if (source != null) refreshNextTick(source.getLocation());
        if (destination != null) refreshNextTick(destination.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().stream().filter(block -> block.getType() == Material.JUKEBOX)
                .map(org.bukkit.block.Block::getLocation).toList().forEach(this::stop);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().stream().filter(block -> block.getType() == Material.JUKEBOX)
                .map(org.bukkit.block.Block::getLocation).toList().forEach(this::stop);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> activate(event.getChunk()));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        String world = event.getWorld().getUID().toString();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        activeJukeboxes.stream()
                .filter(position -> position.world().equals(world)
                        && position.x() >> 4 == chunkX && position.z() >> 4 == chunkZ)
                .toList().forEach(this::stop);
    }

    public void activateLoadedJukeboxes() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) activate(chunk);
        }
    }

    private void activate(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Jukebox jukebox && isCustomDisc(jukebox.getRecord())) start(jukebox, null);
        }
    }

    private void refreshNextTick(Location location) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!location.isChunkLoaded() || !(location.getBlock().getState() instanceof Jukebox jukebox)) {
                stop(location);
            } else if (isCustomDisc(jukebox.getRecord())) {
                start(jukebox, null);
            } else {
                stop(location);
            }
        });
    }

    private void start(Jukebox jukebox, Player actor) {
        jukebox.stopPlaying();
        Optional<SongMetadata> song = songId(jukebox.getRecord()).flatMap(plugin.library()::find);
        if (song.isEmpty()) {
            stop(jukebox.getLocation());
            if (actor != null) actor.sendMessage("The song stored on this record is not in the current song library.");
            return;
        }

        BlockPosition position = BlockPosition.of(jukebox.getLocation());
        activeJukeboxes.add(position);
        boolean started = plugin.playback().playJukebox(position.sourceKey(), jukebox.getLocation().toCenterLocation(),
                song.get(), plugin.settings().defaultVolume(), () -> activeJukeboxes.remove(position));
        if (!started) {
            activeJukeboxes.remove(position);
            if (actor != null) actor.sendMessage("Could not start playback because the source limit was reached.");
        }
    }

    private void stop(Location location) {
        stop(BlockPosition.of(location));
    }

    private void stop(BlockPosition position) {
        plugin.playback().stop(position.sourceKey());
        activeJukeboxes.remove(position);
    }

    private static Jukebox jukeboxHolder(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof Jukebox jukebox ? jukebox : null;
    }

    public void shutdown() {
        activeJukeboxes.forEach(position -> plugin.playback().stop(position.sourceKey()));
        activeJukeboxes.clear();
    }

    static List<Material> discIcons() {
        return DISC_ICONS;
    }

    private record BlockPosition(String world, int x, int y, int z) {
        private static BlockPosition of(Location location) {
            return new BlockPosition(location.getWorld().getUID().toString(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private String sourceKey() {
            return "disc:" + world + ":" + x + ":" + y + ":" + z;
        }
    }
}
