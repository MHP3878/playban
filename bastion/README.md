# Bastion

Anti-cheat, cheat clients, injectors, cheat resource packs and mods in one plugin, with a file on every player and punishments that grow. Paper 1.21.4 to 1.21.11, Java 21. Works on its own; with the client mod Lumio it also sees every mod, injector and resource pack. Bastion was called ModCheck before: its settings, blocked mods, bans and history move over on the first start, and the old permissions (`modcheck.*`) keep working. Delete the old ModCheck jar (if both are installed, Bastion switches ModCheck off).

## Quick start

1. Put `Bastion-2.1.1.jar` in `plugins/` and start the server. Bastion fetches the anti-cheats Grim and Intave itself; restart once more and all three work together (see below).
2. `/setup` and choose how strict: **1 Mild**, **2 Strict** or **3 Hard** (or `/setup 3` at once).
3. For full protection: `/bastion lumio pflicht` (every player needs Lumio).
4. Put your Discord link in the setup menu: it is shown on every ban, kick and warning screen.

## The levels (/setup)

| | 1 Mild | 2 Strict | 3 Hard |
|---|---|---|---|
| Cheat clients, injectors, x-ray mods and packs | out (kick) | out | out |
| Also forbidden | - | Freecam, Baritone, ESP, Seedcracker ... | also Tweakeroo, Item Scroller, AutoFish, MiniHUD |
| Anti-cheat ladder | 3x kick, 3x warning, bans 1 h, 1 d, 7 d, 30 d, forever | kick, 2x warning, bans 1 d, 7 d, 30 d, forever | kick, ban 7 d, 30 d, forever |
| Back with a cheat client/injector/pack | kick again | 3x in 24 h: 1 day ban | 2x in 24 h: **7 days ban** |
| Anti-cheat points until caught | 35 | 25 | 15 |
| Grim's violations until its verdict | 1.5 x the thresholds | the thresholds | 0.7 x the thresholds |
| Injector strict mode | off | off | on |
| Paper anti-x-ray | - | on | on |

Each level can still be changed in single settings afterwards (config.yml or the menus).

## The anti-cheat

**Nobody is punished for one odd moment, and with Grim and Intave nobody for what one anti-cheat says.** A punishment needs three separate moments (findings within three seconds of each other are one moment) within two minutes. Movement alone (fly, speed, step, no fall, timer ...) only kicks; a step up the ladder (and so a ban) only when it happens three times in a day (`anticheat.movement-ban-after`). Only what no real client can send (impossible head angles, riptide without water) catches at once. Explosions, wind charges and big pushes are not judged until the player lands; a fall into water, onto slime, hay, beds or honey is no "no fall".

### Three anti-cheats, one verdict

Bastion works with two free simulation anti-cheats that are used on large servers: **Grim** ([modrinth.com/plugin/grimac](https://modrinth.com/plugin/grimac), GPL-3.0) and **Intave** ([modrinth.com/plugin/intave](https://modrinth.com/plugin/intave), free base version, source available). You only install Bastion:

- **Bastion fetches them itself:** when Grim or Intave is missing, Bastion downloads the official file for your Minecraft version from Modrinth (checked against Modrinth's SHA-512 fingerprint) into `plugins/`. Restart the server once, then they run. Newer versions are downloaded into `plugins/update/` and come with the next restart (`anticheat.consensus.download` / `update`). Intave fetches ProtocolLib itself, which it needs.
- **Set up at once:** Grim's `punishments.yml` gets Bastion's thresholds (copy of the old file kept, Grim reloaded). Intave's `config.yml` is written before its first start: it only watches (`physics-mm: SILENT`, Grim puts players back), no rejoin block, no IP addresses to its cloud, and its kicks and bans become votes (`bastion vote intave ...`). Old files are kept as `*.before-bastion`.
- **Other anti-cheats go:** Vulcan, Spartan, NoCheatPlus, Matrix, AAC and others on the list `anticheat.other-anticheats` would fight with Grim. Bastion switches them off and moves their file to `plugins/Bastion/entfernt/` (take it back from there; `anticheat.remove-others: false` leaves them).
- **The vote:** Grim, Intave and Bastion's own checks each say on their own when they are sure a player cheats. **All three within ten minutes: the next step of the ladder** (with the level of `/setup`). **Two of three: the player is watched** and the team is told. **One: only noted** in the file and told to the team. With only two anti-cheats installed, both must agree, and one watches. A verdict while the server (below 17 TPS) or the player (above 350 ms) lags does not count. Every vote is in the player's file (`/akte`).
- **Only the vote talks to players.** With Grim and Intave there, a finding of one anti-cheat gives the player no warning and no title, the points never go above the limit, and the team only gets the vote's messages (one/two/three of three). Reach and aim are judged by Bastion only against players: a hit animal flies away faster than the attacker sees it (Grim judges every target as the attacker saw it). When Grim puts a player back, Bastion does not take that jump for the player's own movement, and Bastion always judges the movement as the client sent it, before other plugins change it. An old StaffCore (before 0.23, with an anti-cheat of its own) is named at the start and to every admin who joins.
- **Only Grim puts players back.** While Grim is there, Bastion's own checks still judge (and vote) but do not set back, cancel hits or block breaks: two anti-cheats pulling one player make honest players rubber-band.

Tested on Paper 1.21.4 with the real client and all three anti-cheats: falls from 25 and 40 blocks, a jump into water, three creepers (one charged) and TNT right next to the player gave no vote at all.

### Bastion's own checks

Several layers that work together; whatever breaks the game's rules is put back at once (setback), points decide the punishment:

- **The surroundings**: cobweb, ladders and vines, water and lava speed, soul sand and honey, hunger (no sprinting), the claim of standing on the ground (no-fall), elytra only when worn, riptide only with a riptide trident in water, rain (where it really rains) or a bubble column.
- **Combat like Grim**: every player's place is kept tick by tick, so a hit is judged against where the attacker really saw the target (their ping ago): reach is held to the game's 3.0 blocks (+0.1), hits from further do not count. The hitbox check follows the look between two ticks: a hit whose line of sight goes through no place the target could have been is a kill aura; hits far behind the look do not count. Also: hits through walls, several targets at once, hits while blocking or eating (auto block), aim steps (mouse sensitivity), sudden turns, clicks per second and their rhythm, criticals, knockback taken, crystals, anchors, auto totem.
- **Movement forced into the game's physics**: friction, key push and sprint jump in the game's own order, exact jump height, gravity; whatever goes beyond is put back at once, every time. Timer cheats have their extra moves thrown away.
- **Knockback**: every knockback a player gets must show in their next ticks (at least two ticks of it, minus the most the keys can push back). A client that ignores it is moved there by the server, unless a wall is in the way.
- **Elytra**: gliding is computed with the game's own formula tick by tick (look direction, gravity, lift, drag); elytra fly and boost cheats are put back at once. With a rocket, the top speed counts.
- **Boats**: a boat in the air only falls (boat fly cheats are put back with the boat), and boats have their top speeds on water, land and ice.
- **World**: fast break, nuker, scaffold, x-ray (ore statistics and bait ores), bot-like mining.
- **Packets**: timer (too many movement packets), impossible head angles, fast eating, fast bow.
- **Behaviour**: new accounts flooding in, bots writing at once; watched players count one and a half times.

No anti-cheat catches everything: a cheat that stays inside the game's rules (a little reach, a careful aim assist) can look like a good player. The layers make it hard; Lumio required closes the rest on the client side.

## The file (/akte)

Every player has a file: names, first and last seen, clients, mods, Lumio version, resource packs, what the anti-cheat found over all time, every kick, warning and ban with case number, and notes of the team. From the file: punish (next step of the ladder), check client and mods, unban, reset, and the player's own rights.

## Ban screens

Bastion's own screen with reason, duration and end, case number, player id and the Discord link, also when the server's ban system (StaffCore, LiteBans, AdvancedBan, Essentials or the game's list) holds the ban: its `/unban` still works.

## Ranks & rights (/bastion raenge)

The ranks that count on the server (from StaffCore or LuckPerms; without a rank plugin: operators and all players), who has which, and which rank may do what in Bastion (check players, read files, alerts, block and punish, unban, settings, ranks, never checked). Clicking a member sets rights just for that player: as the rank says, allowed or forbidden.

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/setup [1\|2\|3]` | How strict Bastion is: everything at once | `bastion.settings` |
| `/akte [player]` | A player's file, or all files | `bastion.akte` |
| `/bastion ac [points\|watch\|unwatch\|reset\|clear\|replay] <player>` | The anti-cheat: points, watch list, replays of what was found | `bastion.check` |
| `/bastion ac reset <player\|all>` | Back to zero: points, votes of the anti-cheats, watching (also in the file: the milk bucket; `/bastion reset <player>` also clears warnings) | `bastion.check` |
| `/bastion alerts` | Anti-cheat alerts on or off for you | `bastion.check` |
| `/bastion grim setup` | Writes Bastion's thresholds into Grim's `punishments.yml` again and reloads Grim | `bastion.admin` |
| `/bastion grim <player> <check> [vl]` | Grim's verdict (Grim calls it from the console) | console or `bastion.admin` |
| `/bastion vote intave <player> <kick\|ban> [check] [vl]` | Intave's verdict (Intave calls it from the console) | console or `bastion.admin` |
| `/bastion raenge` | Ranks & rights | `bastion.ranks` |
| `/bastion` | The menu: check players, blocked mods, suggestions, settings, banned players | `bastion.check` |
| `/bastion <player>` | All mods of a player; click a mod: **Punish** or **Let pass** | `bastion.check` |
| `/modblock <name>` (`/modsperre`) | Block a mod. `freecam`, `Free Cam`, `freecam-fabric-1.2.1.jar` all work; typos get "did you mean"; Tab suggests known mods | `bastion.block` |
| `/modunblock <name>` | Allow a mod again | `bastion.block` |
| `/inject` | The injection menu, like `/modblock` for mods: **block all injections**, **strict**, what happens, and every known injection client (Vape V4, Vape Lite, Prestige, Drip, Entropy, Doomsday, Slinky, Karma, Sunset, Raven, Koid, Akrien, Itami) with its own action | `bastion.block` (the switches on top: `bastion.settings`) |
| `/inject <client> [kick\|ban\|alert\|default]` | Block one client by name (`/inject vape`, `/inject vape lite ban`); `default` follows "block all" again | `bastion.block` |
| `/inject all on\|off`, `/inject strict on\|off`, `/inject action kick\|ban\|alert`, `/inject on\|off` | The switches (also `alle an\|aus`, `streng an\|aus`, `aktion`) | `bastion.settings` |
| `/inject program <name>`, `/inject list` | Add (or remove) a program name of your own to look for; everything in chat | `bastion.block` |
| `/bastion unban <player>` | Lift a ban, also in the server's ban system (StaffCore, LiteBans, ... or the game's ban list) | `bastion.unban` |
| `/bastion reset <player>` | Clear a player's warnings | `bastion.unban` |
| `/bastion reload` | Reload config and texts | `bastion.reload` |
| `/bastion raum` (`room`) | One simple page for joining: check room on/off, where, what happens with a forbidden mod or without Lumio, Lumio mode, the download link, injection clients, and buttons to see both windows yourself | `bastion.settings` |
| `/bastion fenster` (`window`) | Shows you the window players without Lumio get | `bastion.settings` |
| `/bastion lumio [off\|optional\|required]` | Whether players need the Lumio client mod (also `aus`, `freiwillig`, `pflicht`); without an argument it says what the current mode means. Not required means Bastion does not know every mod, and it says so | `bastion.settings` |

`bastion.notify` gets the alerts, `bastion.bypass` is never checked, `bastion.admin` has everything.

Two permissions are for admins only, because they reach far beyond mods: `bastion.settings` (the general settings: default punishment, allow list, Discord ...) and `bastion.commands` (console commands: the ban command and a command per mod; a console command can do anything on the server). Moderators with `bastion.block` choose punishments per mod, but cannot set commands.

## Who decides what

The server owner sets in the menu what happens for each mod; moderators only press **Punish** or **Let pass**, and Punish does exactly what is set:

- **Action**: only tell the team, kick, warn (kick with "remove it", ban after the warnings), ban, or your own command
- **Reason**: free text; `{mods}` (also `(mod)`, `[mods]` ...) becomes the mod's name, e.g. `No cheating with {mods}!`
- **Ban length**: 10 minutes to permanent
- **Warnings** before the ban
- **Own command**, e.g. `tempban {player} 3d {reason}` for LiteBans or EssentialsX; `{player} {reason} {duration} {mods}` are filled in, several commands separated by `;`
- **Tell the team** and **Discord** on or off

Everything a mod does not set itself comes from the general settings: default action, reason, ban length, warnings, ban with Bastion's own ban or your ban plugin's command, team alerts, Discord webhook, block list or allow list (only listed clients may join), the hidden sign check and the language (English, German).

**Coming back again and again** is one rule, written as one sentence in the settings with three parts to click: "Whoever joins **2** times within **1 day** with a blocked mod gets **a 7-day ban**." Time (off, 10 minutes to 30 days), how often (2 to 5) and the punishment (kick, ban 1 day, 7 days, 30 days, permanent). Every join with any blocked mod counts; when the limit is reached, this punishment comes instead of the normal one.

**Let pass** allows that one mod for that one player for good.

**Bans go through the server's ban system** (`ban-system: auto`): StaffCore, LiteBans, AdvancedBan or EssentialsX when one is installed, else the game's own ban list. So there is one ban list and one ban screen (with the ban ID of StaffCore, for example), and that plugin's `/unban` lifts it; `/bastion unban` does too. Bastion's own bans from before move there on start. `ban-system: modcheck` keeps Bastion's own list; `ban-command` sets your own command instead.

## How mods are found, honestly

1. The **client name** the game sends: `vanilla`, `fabric`, `forge`, `lunarclient` ... (the loader, not the single mods).
2. The **mod channels** a client registers: minimaps, voice chat, schematic mods and every mod that talks to servers. A rule hits a channel whose name space is the mod's name; short names only count exactly.
3. The **hidden sign**, once the player has loaded the world (on Paper before 1.21.9 the player needs solid ground a few blocks below; the check room has stone under its floor for this). The sign editor is opened and closed in the same moment, so it never shows on screen; the client fills in the texts when it opens and sends them back when it closes.
   - Every mod question is a translation key with an empty fallback, wrapped in a `%s` text: a client without the mod sends nothing back, a client with it its own text, old Meteor its raw key. A filter that only looks at the top level of a text never sees the key. Key bindings are asked too (`bind:`), so mods without a language file are seen by their binding.
   - **Controls on every sign, at random places**: a text every client knows (picked at random) must come back filled in, and a made-up key that looks like a real one must come back exactly as the game builds its fallback (two random words, swapped). A sign whose controls fail says nothing about mods: the client changes texts on its own (`hidden-action`). The made-up words also tie each answer to its sign, so a late answer never gets mixed up.
   - **Cross-check**: when a channel already proves a mod (Fabric API, Simple Voice Chat, NeoForge), its text must come back filled in. If it comes back empty, a filter such as ExploitPreventer hides mods (`hidden-action`).
   - Keys come from the mods' own language files (Meteor, ThunderHack, Meteor+, Meteorist, Freecam, SeedcrackerX, Advanced XRay, X to Xray, Sodium, Iris, Mod Menu ...).
4. The **lie detector**: a client that calls itself `vanilla` but registers mod channels or knows mod texts, or sends a client name only cheat clients send (`fake-brands`), is disguised (`disguise-action`). Lunar or Feather without their own channels is shown as a hint on the player's page, never punished.
5. **Proof** for every finding: which channel, which client name, which text on the sign (e.g. »Open GUI« for `key.meteor-client.open-gui`). It shows on the player's page, in `/bastion`, when the mouse is on a team alert, in Discord and in `history.log`.

Everything suspicious about the client itself (blocks the check, a filter, no answer, disguise) is by default only told to the team; each can be set to kick, warn or ban in the settings.

6. **Lumio** (the client mod, `lumio/` in this repository): players who have it send the mods Fabric Loader loaded, with a fingerprint of each jar. Translation filters cannot hide anything from that list; it is cross-checked against the channels and the sign, and a mod seen there but missing in the list marks the list as forged. `lumio.mode`: `off`, `optional` (default) or `required` (only players with Lumio may play; Bedrock players and `bastion.bypass` are free). Lumio players also get cards with keys instead of chat buttons (StaffCore's TPA requests use them).

7. **Injection clients** (Vape, Prestige and the like; Lumio 0.6 or newer): Lumio checks the running game itself, at the start and every few seconds while playing, and tells the server what it finds:
   - **Code without a file**: classes defined straight into the game, the way injection clients put themselves in (strong).
   - **Threads from injected memory** (Windows): a thread that starts in memory belonging to no DLL, the mark of code mapped in by hand (strong).
   - **Java agents** on the command line (strong; `authlib-injector` of some launchers is no cheat), a debugger, or a tool attached later (only told to the team).
   - **Injector programs** running next to the game, by name (those of the known clients plus your own, `/inject program`; strong).
   - DLLs and libraries from unusual folders, code from unknown files (only told to the team, as one line per player).

   What happens is set with `/inject`. A finding that belongs to a **known client** (by its program, its DLL or the beginning of its class names) gets the action set for that client. **Block all** (on by default) puts out every sure finding, a known client or not, with `injection-action`. **Strict** (off by default) also puts out players with only small signs (a DLL from an odd folder, a tool attached later, code from an unknown file); it finds more, but can hit unusual setups. Everything else only goes to the team. The default is a kick with "You have Vape on. Please delete it and join again." With the injection check on, `required` mode needs Lumio 0.6 or newer, and a Lumio that stops reporting for 100 seconds is out (it was switched off or blocked). Lumio sends only file names, never folders.

8. **The check room** (`check-room`, on by default): on joining, a player waits a moment in a closed room of bedrock high above (or deep under) the spawn, with a title and a countdown bar, until the client, the mods, the hidden sign and Lumio are checked. Then "✔ Checked" and back exactly where they were. Inside nothing can be touched, nobody sees anybody, and nobody can be hurt. Without Lumio when it is required, the player stays in the room with a **window**: a download button, a guide (Fabric Installer, Fabric API, Lumio, which folder) and "leave the server" (on 1.21.6+ a real Minecraft dialog, before that a book). A forbidden mod either kicks with its name or keeps the player in the room with a window, whichever you choose. Bedrock players (found through Floodgate/Geyser, not by a client name that could be faked) and `bastion.bypass` skip the room.

**What no server plugin can see**: Wurst and LiquidBounce keep their texts outside the game's language, many hack clients and addons ship no language file, and mods without texts (Lithium, FerriteCore, Baritone) leave nothing to ask for. A client that hides every mod text *and* every mod channel *and* calls itself vanilla (OpSec's vanilla mode) looks exactly like vanilla. For a guarantee use the allow list, or a client mod every player must install.

## Files

`config.yml` (settings and blocked mods), `lang/en.yml`, `lang/de.yml`, `data.yml` (warnings, players let pass, bans), `history.log` (who was found, punished, let pass, banned, unbanned).
