package dev.customjukebox.sign;

public enum RedstoneMode {
    TOGGLE("Toggle"), PULSE("Pulse"), IGNORE("Ignore");

    private final String display;

    RedstoneMode(String display) { this.display = display; }
    public String display() { return display; }
    public RedstoneMode next() { return values()[(ordinal() + 1) % values().length]; }
}
