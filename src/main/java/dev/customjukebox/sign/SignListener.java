package dev.customjukebox.sign;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.song.SongMetadata;
import dev.customjukebox.ui.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SignListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final CustomJukeboxPlugin plugin;
    /** Signs touched by redstone this tick; evaluated once on the next tick when power has settled. */
    private final Map<SignManager.BlockKey, Location> pendingPower = new LinkedHashMap<>();

    public SignListener(CustomJukeboxPlugin plugin) { this.plugin = plugin; }

    /** Configured signs hold the jukebox settings, so only permitted players may rewrite them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProtectedSignChange(SignChangeEvent event) {
        if (event.getPlayer().hasPermission("customjukebox.sign.place")) return;
        if (event.getBlock().getState() instanceof Sign sign && plugin.signs().read(sign).isPresent()) {
            event.setCancelled(true);
            Text.error(event.getPlayer(), "You do not have permission to edit jukebox signs.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String first = PLAIN.serialize(event.line(0)).trim();
        if (!first.equalsIgnoreCase("[jukebox]")) return;
        if (!event.getPlayer().hasPermission("customjukebox.sign.place")) {
            Text.error(event.getPlayer(), "You do not have permission to create jukebox signs.");
            return;
        }
        Sign sign = (Sign) event.getBlock().getState();

        SignConfig previous = plugin.signs().read(sign).orElse(
                new SignConfig("", plugin.settings().defaultVolume(), false, RedstoneMode.IGNORE));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(event.getBlock().getState() instanceof Sign current)) return;
            plugin.signs().write(current, previous, true);
            plugin.guis().openSign(event.getPlayer(), current);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        if (!event.getAction().isRightClick()) return;
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) return;
        var config = plugin.signs().read(sign);
        if (config.isEmpty()) return;
        // Sneak-interact keeps vanilla sign editing available.
        if (event.getPlayer().isSneaking()) return;
        event.setCancelled(true);
        if (!event.getPlayer().hasPermission("customjukebox.sign.place")) {
            String title = plugin.library().find(config.get().songId()).map(SongMetadata::displayTitle).orElse(null);
            if (title == null) Text.info(event.getPlayer(), "This jukebox has no song yet.");
            else Text.info(event.getPlayer(), plugin.signs().isPlaying(sign) ? "Now playing: " : "This jukebox plays: ", title);
            return;
        }
        plugin.guis().openSign(event.getPlayer(), sign);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getState() instanceof Sign sign && plugin.signs().read(sign).isPresent()) {
            plugin.signs().remove(sign.getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        // Physics fires constantly; check the material before taking a block-state snapshot.
        if (!Tag.ALL_SIGNS.isTagged(event.getBlock().getType())) return;
        if (!(event.getBlock().getState() instanceof Sign sign) || plugin.signs().read(sign).isEmpty()) return;
        var location = sign.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(location.getBlock().getState() instanceof Sign current) || plugin.signs().read(current).isEmpty()) {
                plugin.signs().remove(location);
            }
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!event.getChunk().isLoaded()) return;
            for (var state : event.getChunk().getTileEntities()) {
                if (state instanceof Sign sign && plugin.signs().read(sign).isPresent()) {
                    plugin.signs().track(sign);
                    plugin.signs().handlePower(sign);
                }
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        queueNear(event.getBlock());
    }

    /** Redstone blocks power their surroundings without firing a {@link BlockRedstoneEvent}. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.REDSTONE_BLOCK) queueNear(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.REDSTONE_BLOCK) queueNear(event.getBlock());
    }

    /**
     * Queues every registered sign that a power change at {@code source} could affect. Redstone
     * changes are applied after their events fire, so the signs are evaluated on the next tick,
     * once per tick no matter how many events touched them.
     */
    private void queueNear(Block source) {
        // A source one block outside the trigger cube can still power a block inside it.
        int radius = plugin.settings().redstoneRadius() + 1;
        List<Sign> nearby = plugin.signs().signsNear(source, radius);
        if (nearby.isEmpty()) return;
        boolean schedule = pendingPower.isEmpty();
        for (Sign sign : nearby) pendingPower.putIfAbsent(SignManager.BlockKey.of(sign.getLocation()), sign.getLocation());
        if (!schedule) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<Location> locations = new ArrayList<>(pendingPower.values());
            pendingPower.clear();
            for (Location location : locations) {
                if (location.isChunkLoaded() && location.getBlock().getState() instanceof Sign sign
                        && plugin.signs().read(sign).isPresent()) {
                    plugin.signs().handlePower(sign);
                }
            }
        });
    }
}
