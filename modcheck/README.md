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
| `/modcheck reset <player>` | Clear a player's warnings | `modcheck.check` |
| `/modcheck reload` | Reload config and texts | `modcheck.reload` |

`modcheck.notify` gets the alerts, `modcheck.bypass` is never checked, `modcheck.admin` has everything.

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
3. A **hidden sign** on join: for a blink the client gets a sign editor with translation keys of the blocked mods, and a client that has such a mod fills in its own text. On Paper 1.21.9+ the sign only exists for the player; on older servers a real sign stands two blocks under the player for two ticks and the block is put back.

A full list of every mod is something no server is sent. Mods that never talk to the server and hide their names (many cheat clients pretend to be plain Fabric) are only found when they give themselves away. For a guarantee use the allow list, or a client mod every player must install.

## Files

`config.yml` (settings and blocked mods), `lang/en.yml`, `lang/de.yml`, `data.yml` (warnings, players let pass, bans), `history.log` (who was found, punished, let pass, banned, unbanned).
