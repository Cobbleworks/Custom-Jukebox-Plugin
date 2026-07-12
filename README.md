# CustomJukebox

Paper 26.1.2 plugin for lazy-indexed `.nbs` playback from signs and a personal
GUI. Requires Java 25 and the supplied **NoteBlockAPI 1.7.0** plugin jar.

## Build and install

```bash
mvn clean package
```

Install `target/custom-jukebox-1.0.0.jar` and `NoteBlockAPI-1.7.0.jar` in the
server's `plugins/` directory. Songs go under
`plugins/CustomJukebox/songs/`; subfolders appear as navigable chests in the
song picker.

This release targets Paper, not Folia. NoteBlockAPI 1.7.0 does not use Folia's
region scheduler, so `folia-supported` is deliberately false.

## Sign setup

Write `[jukebox]` on line 1. The picker opens one tick after the edit closes;
volume and looping are configured there. Right-click a configured sign for its
GUI, or sneak-right-click to use vanilla text editing.

Redstone modes are `Toggle` (play while powered), `Pulse` (rising edge starts),
and `Ignore` (GUI only). Sign configuration is stored on the sign PDC; an
independent `signs.yml` location index powers `/jukebox list-signs`.

## Commands

- `/jukebox play` — personal picker and controls
- `/jukebox play <path or title>` — play directly
- `/jukebox stop` — stop personal playback
- `/jukebox reload` — rescan songs (admin)
- `/jukebox list-signs` — list indexed signs (admin)

Permissions: `customjukebox.play`, `customjukebox.sign.place`, and
`customjukebox.admin`.

Personal playback shows the current song in the player's action bar. Middle-
click a song in the personal picker to receive a named disc; inserting that
disc into a jukebox plays the custom song from the jukebox location.
