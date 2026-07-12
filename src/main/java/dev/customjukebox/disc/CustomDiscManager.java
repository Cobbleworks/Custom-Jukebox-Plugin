package dev.customjukebox.disc;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.song.SongMetadata;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CustomDiscManager implements Listener {
    private static final List<Material> MUSIC_DISCS = Arrays.stream(Material.values())
            .filter(material -> material.name().startsWith("MUSIC_DISC_"))
            .sorted(Comparator.comparing(Enum::name))
            .toList();

    private final CustomJukeboxPlugin plugin;
    private final NamespacedKey songKey;

    public CustomDiscManager(CustomJukeboxPlugin plugin) {
        this.plugin = plugin;
        songKey = new NamespacedKey(plugin, "custom_song");
    }

    public void give(Player player, SongMetadata song) {
        ItemStack disc = create(song);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(disc);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.sendMessage("Created song disc: " + song.displayTitle());
    }

    public ItemStack create(SongMetadata song) {
        Material material = MUSIC_DISCS.get(Math.floorMod(song.id().hashCode(), MUSIC_DISCS.size()));
        ItemStack disc = ItemStack.of(material);
        disc.editMeta(meta -> {
            meta.displayName(Component.text(song.displayTitle(), NamedTextColor.GOLD));
            meta.lore(List.of(Component.text("Custom Jukebox song", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(songKey, PersistentDataType.STRING, song.id());
        });
        return disc;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getHand() == null) return;
        if (!(event.getClickedBlock().getState() instanceof Jukebox jukebox)) return;

        if (songId(jukebox.getRecord()).isPresent()) {
            event.setCancelled(true);
            plugin.playback().stop(sourceKey(jukebox.getLocation()));
            jukebox.eject();
            return;
        }

        Optional<String> heldSongId = songId(event.getItem());
        if (heldSongId.isEmpty() || jukebox.hasRecord()) return;

        event.setCancelled(true);
        Optional<SongMetadata> heldSong = plugin.library().find(heldSongId.get());
        if (heldSong.isEmpty()) {
            event.getPlayer().sendMessage("That song is no longer available in the song library.");
            return;
        }
        ItemStack record = event.getItem().asOne();
        jukebox.setRecord(record);
        if (!jukebox.update(true, false)) {
            event.getPlayer().sendMessage("Could not insert that song disc.");
            return;
        }

        consumeHeldItem(event.getPlayer(), event.getHand(), event.getItem());
        String key = sourceKey(jukebox.getLocation());
        boolean started = plugin.playback().playJukebox(key, jukebox.getLocation().toCenterLocation(),
                heldSong.get(), plugin.settings().defaultVolume());
        if (!started) {
            jukebox.eject();
            event.getPlayer().sendMessage("Could not start playback (the source limit may be reached).");
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getState() instanceof Jukebox jukebox && songId(jukebox.getRecord()).isPresent()) {
            plugin.playback().stop(sourceKey(jukebox.getLocation()));
        }
    }

    private Optional<String> songId(ItemStack item) {
        if (item == null || item.isEmpty()) return Optional.empty();
        String songId = item.getPersistentDataContainer().get(songKey, PersistentDataType.STRING);
        return Optional.ofNullable(songId);
    }

    private static void consumeHeldItem(Player player, EquipmentSlot hand, ItemStack held) {
        ItemStack remainder = held.getAmount() == 1 ? ItemStack.empty() : held.asQuantity(held.getAmount() - 1);
        player.getInventory().setItem(hand, remainder);
    }

    private static String sourceKey(Location location) {
        return "jukebox:" + location.getWorld().getName() + ";" + location.getBlockX() + ";"
                + location.getBlockY() + ";" + location.getBlockZ();
    }
}
