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

1. The **client name** the game sends: `vanilla`, `fabric`, `forge`, `lunarclient` ... (the loader, not the single mods).
2. The **mod channels** a client registers: minimaps, voice chat, schematic mods and every mod that talks to servers. A rule hits a channel whose name space is the mod's name; short names only count exactly.
3. The **hidden sign**, once the player has loaded the world. The sign editor is opened and closed in the same moment, so it never shows on screen; the client fills in the texts when it opens and sends them back when it closes.
   - Every mod question is a translation key with an empty fallback, wrapped in a `%s` text: a client without the mod sends nothing back, a client with it its own text, old Meteor its raw key. A filter that only looks at the top level of a text never sees the key. Key bindings are asked too (`bind:`), so mods without a language file are seen by their binding.
   - **Controls on every sign, at random places**: a text every client knows (picked at random) must come back filled in, and a made-up key that looks like a real one must come back exactly as the game builds its fallback (two random words, swapped). A sign whose controls fail says nothing about mods: the client changes texts on its own (`hidden-action`). The made-up words also tie each answer to its sign, so a late answer never gets mixed up.
   - **Cross-check**: when a channel already proves a mod (Fabric API, Simple Voice Chat, NeoForge), its text must come back filled in. If it comes back empty, a filter such as ExploitPreventer hides mods (`hidden-action`).
   - Keys come from the mods' own language files (Meteor, ThunderHack, Meteor+, Meteorist, Freecam, SeedcrackerX, Advanced XRay, X to Xray, Sodium, Iris, Mod Menu ...).
4. The **lie detector**: a client that calls itself `vanilla` but registers mod channels or knows mod texts, or sends a client name only cheat clients send (`fake-brands`), is disguised (`disguise-action`). Lunar or Feather without their own channels is shown as a hint on the player's page, never punished.
5. **Proof** for every finding: which channel, which client name, which text on the sign (e.g. »Open GUI« for `key.meteor-client.open-gui`). It shows on the player's page, in `/modcheck`, when the mouse is on a team alert, in Discord and in `history.log`.

Everything suspicious about the client itself (blocks the check, a filter, no answer, disguise) is by default only told to the team; each can be set to kick, warn or ban in the settings.

6. **Lumio** (the client mod, `lumio/` in this repository): players who have it send the mods Fabric Loader loaded, with a fingerprint of each jar. Translation filters cannot hide anything from that list; it is cross-checked against the channels and the sign, and a mod seen there but missing in the list marks the list as forged. `lumio.mode`: `off`, `optional` (default) or `required` (only players with Lumio may play; Bedrock players and `modcheck.bypass` are free). Lumio players also get cards with keys instead of chat buttons (StaffCore's TPA requests use them).

**What no server plugin can see**: Wurst and LiquidBounce keep their texts outside the game's language, many hack clients and addons ship no language file, and mods without texts (Lithium, FerriteCore, Baritone) leave nothing to ask for. A client that hides every mod text *and* every mod channel *and* calls itself vanilla (OpSec's vanilla mode) looks exactly like vanilla. For a guarantee use the allow list, or a client mod every player must install.

## Files

`config.yml` (settings and blocked mods), `lang/en.yml`, `lang/de.yml`, `data.yml` (warnings, players let pass, bans), `history.log` (who was found, punished, let pass, banned, unbanned).
