# CustomJukebox

Paper 26.1.2 plugin for lazy-indexed `.nbs` playback from signs and a personal
GUI. Requires Java 25 and the supplied **NoteBlockAPI 1.7.0** plugin jar.

## Build and install

```bash
mvn clean package
```

Install `target/custom-jukebox-1.0.0.jar` and `NoteBlockAPI-1.7.0.jar` in the
server's `plugins/` directory. Songs go under
`plugins/CustomJukebox/songs/`; subfolders are supported.

This release targets Paper, not Folia. NoteBlockAPI 1.7.0 does not use Folia's
region scheduler, so `folia-supported` is deliberately false.

## Sign setup

Write `[jukebox]` on line 1, an optional volume (`0`–`10`, default `3`) on
line 2, and optional `true`/`false` loop state on line 3. The picker opens one
tick after the edit closes. Right-click a configured sign for its GUI; sneak-
right-click uses vanilla text editing and re-applies lines 2/3 afterward.

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
