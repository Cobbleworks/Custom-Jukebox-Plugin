package dev.customjukebox.sign;

import dev.customjukebox.CustomJukeboxPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.LinkedHashSet;
import java.util.Set;

public final class SignListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final BlockFace[] NEIGHBORS = { BlockFace.SELF, BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };
    private final CustomJukeboxPlugin plugin;

    public SignListener(CustomJukeboxPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String first = PLAIN.serialize(event.line(0)).trim();
        if (!first.equalsIgnoreCase("[jukebox]")) return;
        Sign sign = (Sign) event.getBlock().getState();
        boolean existing = plugin.signs().read(sign).isPresent();
        if (!existing && !event.getPlayer().hasPermission("customjukebox.sign.place")) {
            event.getPlayer().sendMessage("You do not have permission to create jukebox signs.");
            return;
        }

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
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) return;
        if (plugin.signs().read(sign).isEmpty()) return;
        // Sneak-interact keeps vanilla sign editing available.
        if (event.getPlayer().isSneaking()) return;
        event.setCancelled(true);
        if (!event.getPlayer().hasPermission("customjukebox.sign.place")) {
            event.getPlayer().sendMessage("You do not have permission to configure jukebox signs.");
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
            for (var state : event.getChunk().getTileEntities()) {
                if (state instanceof Sign sign && plugin.signs().read(sign).isPresent()) plugin.signs().handlePower(sign);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        Set<Block> candidates = new LinkedHashSet<>();
        for (BlockFace face : NEIGHBORS) candidates.add(event.getBlock().getRelative(face));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Block block : candidates) {
                if (block.getState() instanceof Sign sign && plugin.signs().read(sign).isPresent()) {
                    plugin.signs().handlePower(sign);
                }
            }
        });
    }
}
