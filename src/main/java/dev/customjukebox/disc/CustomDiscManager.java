package dev.customjukebox.disc;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.song.SongMetadata;
import dev.customjukebox.ui.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates song-bound records and keeps physical jukebox playback synchronized
 * with the record stored in each block.
 *
 * <p>Custom records have their vanilla {@code jukebox_playable} component removed, so the
 * server never starts a vanilla disc song for them. Insertion by players and hoppers is
 * handled here instead, and only the plugin's NBS playback is audible.
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
    /** Jukeboxes with running custom playback, mapped to the song id they are playing. */
    private final Map<BlockPosition, String> activeJukeboxes = new ConcurrentHashMap<>();
    /** Players whose main-hand click was already handled this tick; suppresses the follow-up off-hand click. */
    private final Set<UUID> handledThisTick = ConcurrentHashMap.newKeySet();
    private final BukkitTask watchdog;

    public CustomDiscManager(CustomJukeboxPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "custom_disc");
        this.songKey = new NamespacedKey(plugin, "custom_disc_song");
        this.watchdog = plugin.getServer().getScheduler().runTaskTimer(plugin, this::verifyActiveJukeboxes, 20L, 20L);
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
        return silenced(disc);
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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) return;

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == EquipmentSlot.OFF_HAND && handledThisTick.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        PlayerInventory inventory = player.getInventory();
        // Vanilla skips block interaction when sneaking with an item in either hand.
        if (player.isSneaking() && (!inventory.getItemInMainHand().isEmpty()
                || !inventory.getItemInOffHand().isEmpty())) return;
        if (!(block.getState() instanceof Jukebox jukebox)) return;

        ItemStack record = jukebox.getRecord();
        if (isCustomDisc(record)) {
            event.setCancelled(true);
            markHandled(player);
            stop(block.getLocation());
            silenceVanilla(jukebox, record);
            jukebox.eject();
            player.swingHand(hand);
            return;
        }
        // A vanilla record is ejected by vanilla; never stack a second record on top of it.
        if (!record.isEmpty()) return;

        ItemStack held = inventory.getItem(hand);
        if (!isCustomDisc(held)) return;
        event.setCancelled(true);
        markHandled(player);
        if (!player.hasPermission("customjukebox.disc.use")) {
            Text.error(player, "You do not have permission to use custom records.");
            return;
        }

        insert(jukebox, held.asOne(), player);
        if (player.getGameMode() != GameMode.CREATIVE) held.subtract(1);
        player.swingHand(hand);
    }

    /**
     * Custom records carry no vanilla playable component, so vanilla hoppers would reject them.
     * Transfers into a jukebox are therefore cancelled and performed manually on the next tick,
     * after the hopper has restored its own stack.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRecordTransfer(InventoryMoveItemEvent event) {
        Jukebox destination = jukeboxHolder(event.getDestination());
        if (destination == null || !isCustomDisc(event.getItem())) return;
        event.setCancelled(true);
        if (!destination.getRecord().isEmpty()) return;

        Inventory source = event.getSource();
        ItemStack disc = event.getItem().asOne();
        Location location = destination.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!location.isChunkLoaded() || !(location.getBlock().getState() instanceof Jukebox jukebox)
                    || !jukebox.getRecord().isEmpty()) return;
            if (!source.removeItem(disc.clone()).isEmpty()) return;
            insert(jukebox, disc, null);
        });
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
                .map(Block::getLocation).toList().forEach(this::stop);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().stream().filter(block -> block.getType() == Material.JUKEBOX)
                .map(Block::getLocation).toList().forEach(this::stop);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> activate(event.getChunk()));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        activeJukeboxes.keySet().stream()
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
        if (!chunk.isLoaded()) return;
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Jukebox jukebox && isCustomDisc(jukebox.getRecord())) start(jukebox.getLocation(), null);
        }
    }

    private void refreshNextTick(Location location) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!location.isChunkLoaded() || !(location.getBlock().getState() instanceof Jukebox jukebox)) {
                stop(location);
            } else if (isCustomDisc(jukebox.getRecord())) {
                String playing = activeJukeboxes.get(BlockPosition.of(location));
                if (playing == null || !playing.equals(songId(jukebox.getRecord()).orElse(null))) {
                    start(location, null);
                }
            } else {
                stop(location);
            }
        });
    }

    /**
     * Stops playback for jukeboxes that disappeared or lost their record without an event
     * (commands, other plugins, unhandled block removal), so no song keeps playing at an empty spot.
     */
    private void verifyActiveJukeboxes() {
        for (Map.Entry<BlockPosition, String> entry : activeJukeboxes.entrySet()) {
            BlockPosition position = entry.getKey();
            if (!plugin.playback().isActive(position.sourceKey())) {
                activeJukeboxes.remove(position, entry.getValue());
                continue;
            }
            World world = plugin.getServer().getWorld(position.world());
            if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
                stop(position);
                continue;
            }
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            if (block.getType() != Material.JUKEBOX || !(block.getState(false) instanceof Jukebox jukebox)
                    || !entry.getValue().equals(songId(jukebox.getRecord()).orElse(null))) {
                stop(position);
            }
        }
    }

    private void insert(Jukebox jukebox, ItemStack disc, Player actor) {
        jukebox.setRecord(silenced(disc));
        jukebox.update(true, false);
        start(jukebox.getLocation(), actor);
    }

    private void start(Location location, Player actor) {
        if (!location.isChunkLoaded() || !(location.getBlock().getState() instanceof Jukebox jukebox)) {
            stop(location);
            return;
        }
        ItemStack record = jukebox.getRecord();
        if (!isCustomDisc(record)) {
            stop(location);
            return;
        }
        silenceVanilla(jukebox, record);

        Optional<String> id = songId(record);
        Optional<SongMetadata> song = id.flatMap(plugin.library()::find);
        if (song.isEmpty()) {
            stop(location);
            if (actor != null) Text.error(actor, "This record's song is not in the song library.");
            return;
        }

        BlockPosition position = BlockPosition.of(location);
        activeJukeboxes.put(position, id.get());
        boolean started = plugin.playback().playJukebox(position.sourceKey(), location.toCenterLocation(),
                song.get(), plugin.settings().defaultVolume(), () -> activeJukeboxes.remove(position));
        if (!started) {
            activeJukeboxes.remove(position);
            if (actor != null) Text.error(actor, "Could not start playback. The server's playback limit may be reached.");
        }
    }

    /**
     * Records created before the playable component was stripped still trigger vanilla playback.
     * Replacing them with a silenced copy makes vanilla stop its song (and notify clients), and
     * the ejected or dropped record stays silent afterwards.
     */
    private void silenceVanilla(Jukebox jukebox, ItemStack record) {
        if (record.hasData(DataComponentTypes.JUKEBOX_PLAYABLE)) {
            jukebox.setRecord(silenced(record));
            jukebox.update(true, false);
        }
        jukebox.stopPlaying();
    }

    private static ItemStack silenced(ItemStack item) {
        ItemStack copy = item.clone();
        copy.unsetData(DataComponentTypes.JUKEBOX_PLAYABLE);
        return copy;
    }

    private void markHandled(Player player) {
        UUID id = player.getUniqueId();
        if (handledThisTick.add(id)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> handledThisTick.remove(id));
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
        watchdog.cancel();
        activeJukeboxes.keySet().forEach(position -> plugin.playback().stop(position.sourceKey()));
        activeJukeboxes.clear();
        handledThisTick.clear();
    }

    static List<Material> discIcons() {
        return DISC_ICONS;
    }

    private record BlockPosition(UUID world, int x, int y, int z) {
        private static BlockPosition of(Location location) {
            return new BlockPosition(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private String sourceKey() {
            return "disc:" + world + ":" + x + ":" + y + ":" + z;
        }
    }
}
