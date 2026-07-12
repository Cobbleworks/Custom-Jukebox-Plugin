package dev.customjukebox.sign;

public record SignConfig(String songId, int volume, boolean loop, RedstoneMode redstoneMode) {
    public SignConfig withSong(String songId) { return new SignConfig(songId, volume, loop, redstoneMode); }
    public SignConfig withVolume(int volume) { return new SignConfig(songId, volume, loop, redstoneMode); }
    public SignConfig withLoop(boolean loop) { return new SignConfig(songId, volume, loop, redstoneMode); }
    public SignConfig withRedstone(RedstoneMode mode) { return new SignConfig(songId, volume, loop, mode); }
}
