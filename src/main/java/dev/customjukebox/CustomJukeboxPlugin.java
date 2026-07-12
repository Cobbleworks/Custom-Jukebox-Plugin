package dev.customjukebox;

import dev.customjukebox.command.JukeboxCommand;
import dev.customjukebox.config.PluginSettings;
import dev.customjukebox.disc.CustomDiscManager;
import dev.customjukebox.gui.JukeboxGui;
import dev.customjukebox.playback.PlaybackManager;
import dev.customjukebox.sign.SignListener;
import dev.customjukebox.sign.SignManager;
import dev.customjukebox.song.SongLibrary;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class CustomJukeboxPlugin extends JavaPlugin {
    private PluginSettings settings;
    private SongLibrary library;
    private PlaybackManager playback;
    private SignManager signs;
    private JukeboxGui guis;
    private CustomDiscManager discs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        library = new SongLibrary(this);
        SongLibrary.ScanResult scan = library.scan();
        playback = new PlaybackManager(this);
        signs = new SignManager(this);
        discs = new CustomDiscManager(this);
        guis = new JukeboxGui(this);

        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        getServer().getPluginManager().registerEvents(guis, this);
        getServer().getPluginManager().registerEvents(discs, this);
        PluginCommand command = Objects.requireNonNull(getCommand("jukebox"));
        JukeboxCommand handler = new JukeboxCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        getServer().getScheduler().runTask(this, signs::activateLoadedSigns);

        getLogger().info("Indexed " + scan.songs() + " NBS songs (" + scan.invalid() + " invalid)." );
        getLogger().info("Volume range " + settings.minVolume() + "-" + settings.maxVolume()
                + ", default " + settings.defaultVolume() + "; source cap " + settings.maxActiveSources() + ".");
    }

    @Override public void onDisable() {
        if (playback != null) playback.stopAll();
    }

    public void reloadSettings() {
        reloadConfig();
        settings = PluginSettings.from(getConfig());
    }

    public PluginSettings settings() { return settings; }
    public SongLibrary library() { return library; }
    public PlaybackManager playback() { return playback; }
    public SignManager signs() { return signs; }
    public JukeboxGui guis() { return guis; }
    public CustomDiscManager discs() { return discs; }
}
