# ModCheck

See which client and mods players use, block mods just by their name, and let the settings decide what happens: a warning, a kick, a ban or your own command. Paper 1.21.4 to 1.21.11, Java 21. Works on its own, no other plugin needed.

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/modcheck` | The menu: check players, blocked mods, suggestions, settings, banned players | `modcheck.check` |
| `/modcheck <player>` | All mods of a player; click a mod: **Punish** or **Let pass** | `modcheck.check` |
| `/modblock <name>` (`/modsperre`) | Block a mod. `freecam`, `Free Cam`, `freecam-fabric-1.2.1.jar` all work; typos get "did you mean"; Tab suggests known mods | `modcheck.block` |
| `/modunblock <name>` | Allow a mod again | `modcheck.block` |
| `/modcheck unban <player>` | Lift a ModCheck ban | `modcheck.unban` |
| `/modcheck reset <player>` | Clear a player's warnings | `modcheck.unban` |
| `/modcheck reload` | Reload config and texts | `modcheck.reload` |

`modcheck.notify` gets the alerts, `modcheck.bypass` is never checked, `modcheck.admin` has everything.

Two permissions are for admins only, because they reach far beyond mods: `modcheck.settings` (the general settings: default punishment, allow list, Discord ...) and `modcheck.commands` (console commands: the ban command and a command per mod; a console command can do anything on the server). Moderators with `modcheck.block` choose punishments per mod, but cannot set commands.

## Who decides what

The server owner sets in the menu what happens for each mod; moderators only press **Punish** or **Let pass**, and Punish does exactly what is set:

- **Action**: only tell the team, kick, warn (kick with "remove it", ban after the warnings), ban, or your own command
- **Reason**: free text; `{mods}` (also `(mod)`, `[mods]` ...) becomes the mod's name, e.g. `No cheating with {mods}!`
- **Ban length**: 10 minutes to permanent
- **Warnings** before the ban
- **Own command**, e.g. `tempban {player} 3d {reason}` for LiteBans or EssentialsX; `{player} {reason} {duration} {mods}` are filled in, several commands separated by `;`
- **Tell the team** and **Discord** on or off

Everything a mod does not set itself comes from the general settings: default action, reason, ban length, warnings, ban with ModCheck's own ban or your ban plugin's command, team alerts, Discord webhook, block list or allow list (only listed clients may join), the hidden sign check and the language (English, German).

**Coming back again and again** is one rule, written as one sentence in the settings with three parts to click: "Whoever joins **2** times within **1 day** with a blocked mod gets **a 7-day ban**." Time (off, 10 minutes to 30 days), how often (2 to 5) and the punishment (kick, ban 1 day, 7 days, 30 days, permanent). Every join with any blocked mod counts; when the limit is reached, this punishment comes instead of the normal one.

**Let pass** allows that one mod for that one player for good.

## How mods are found, honestly

1. The **client name** the game sends: `vanilla`, `fabric`, `forge`, `lunarclient`, `feather` ... (the loader, not the single mods).
2. The **mod channels** a client registers: minimaps, voice chat, schematic mods and every mod that talks to servers.
3. A **hidden sign** once the player has loaded the world: the client gets a sign editor that is opened and closed in the same moment (it never shows on screen) with translation keys of the blocked mods (with an empty fallback) and key bindings. A client without the mod sends nothing back, a client with it its own text or bound key. Old Meteor versions that hide their keys send the raw key back, which gives them away too. On Paper 1.21.9+ the sign only exists for the player; on older servers a real sign stands two blocks under the player for a moment and the block is put back. A dead player is checked after the respawn.
   - Controls guard against false alarms: `key.jump` must come back filled in, and a made-up key must come back empty. A client that fails either changes texts on its own (a mod that hides mods); then nothing it says counts and the team gets a note "blocks the mod check".
   - `/modcheck <player>` also asks for well-known everyday mods (Sodium, Iris, Mod Menu, Cloth Config, EntityCulling ...). Their keys come from the mods' own language files.
   - A client that blocks the check (`hidden-action`) or twice does not answer the sign (`no-answer-action`) is reported to the team; both can be set to kick, warn or ban in the settings.
   - Only one check per player at a time, once per join (not again after every death or world change), and not while a chest or shop is open (it waits up to 15 seconds).
   - On older Paper the real sign never replaces unbreakable or special blocks, cannot be broken, blown up or pushed while it stands, and every block is put back, also when the server stops.
   - Cannot be seen this way: mods without texts (Lithium, FerriteCore, ImmediatelyFast, Baritone) and Wurst, which loads its texts outside the game's language.

A full list of every mod is something no server is sent. Mods that never talk to the server and hide their names (many cheat clients pretend to be plain Fabric) are only found when they give themselves away. For a guarantee use the allow list, or a client mod every player must install.

## Files

`config.yml` (settings and blocked mods), `lang/en.yml`, `lang/de.yml`, `data.yml` (warnings, players let pass, bans), `history.log` (who was found, punished, let pass, banned, unbanned).
