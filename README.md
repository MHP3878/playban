# StaffCore

Staff tools in one plugin for Paper 1.21.4 to 1.21.11. Formerly called PlayBan; an existing `plugins/PlayBan` folder is taken over automatically.

## PlayBan: punish without banning

Punish players without banning them. A punished player stays on the server, but:

- other players cannot see them (they still see everyone else)
- they cannot break, place, interact, attack, drop or pick up anything
- they cannot chat and can only use the commands you allow, plus `/ticket`
- they get tasks, and every finished task shortens the punishment

StaffCore also hands out warnings and real bans. You define categories such as X-Ray, Cheating or Spam, each with its own ladder: what happens on the first, second, third offense. The team only picks the category, the plugin works out the punishment.

Everything is controlled through in-game menus: punishing, categories, reviewing messages, tickets, the task editor and all settings.

## Requirements

- Paper 1.21.4 to 1.21.11, Java 21
- Optional: Vault plus an economy plugin for the "give money" task
- Optional: the plugin Simple Voice Chat for voice group moderation

## Install

1. Put `StaffCore-<version>.jar` into the `plugins` folder and restart the server.
2. Run `/sc menu`.

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/sc`, `/staffcore` (also `/playban`) | Team: main menu. Punished player: own tasks | - |
| `/sc <player>` or `/punish <player>` | Pick a category for a player in a menu | `staffcore.staff` |
| `/sc apply <player> <category>` | Punish by category, also works from the console | `staffcore.staff` |
| `/sc punish <player> <time> [reason]` | PlayBan with a duration of your choice | `staffcore.staff` |
| `/warn <player> [reason]` | Warn a player | `staffcore.staff` |
| `/ban <player> <time or perm> [reason]` | Real ban | `staffcore.staff` |
| `/sc specban <player> <time or perm> [reason]` | Spectator ban: may join, but is stuck and can only write tickets | `staffcore.staff` |
| `/unban <player or ban-ID>` | Lift a ban or spectator ban | `staffcore.staff` |
| `/vanish` | Turn invisible to players; the team still sees you. Your name is left out of kill, join, quit and advancement messages, the server list does not count you, and your chat goes to the team unless it starts with `!` | `staffcore.vanish` |
| `/guide` | Every command with what it does and an example | - |
| `/spawn` | Teleport to spawn after a short countdown; staff go at once and can send others with `/spawn <player>` | `staffcore.spawn` |
| `/setspawn` | Set the spawn where you stand | `staffcore.admin` |
| `/appeal <ban-ID> <text>` | Ask to be unbanned, with the ID from the ban screen | - |
| `/sc note <player> <text>` | Write a note about a player | `staffcore.staff` |
| `/sc profile <player>` | Notes and punishments of a player | `staffcore.staff` |
| `/sc pardon <player>` | End a PlayBan | `staffcore.staff` |
| `/sc addtime` / `removetime <player> <time>` | Change the remaining time | `staffcore.staff` |
| `/sc info <player>`, `list`, `history <player>` | Look things up | `staffcore.staff` |
| `/sc tasks` | Own tasks (for punished team members) | - |
| `/sc lang <code>` | Switch language (`en`, `de`) | `staffcore.admin` |
| `/sc reload` | Reload config, tasks and texts | `staffcore.admin` |
| `/ticket` | Open a ticket: pick a reason, then describe it | `staffcore.ticket` |
| `/ticket <message>` | Open a ticket directly | `staffcore.ticket` |
| `/ticket show [reason]` | Ticket overview for the team, optionally filtered | `staffcore.staff` |
| `/ticket open <number>` | Open one ticket | `staffcore.staff` |
| `/ticket stats` | Who handled how many tickets | `staffcore.staff` |
| `/ticket back` | Return after teleporting from a ticket | `staffcore.staff` |
| `/voice` | Voice groups: join unseen, kick, mute, ban, close, passwords | `staffcore.voice` |
| `/voice report` | Report a voice group to the team | - |
| `/voice manage` | Kick or ban in a voice group you created | - |
| `/voice speak` | Switch your microphone on after the team pulled you into a ticket voice group | - |

Times look like `30m`, `2h`, `1d 12h`.

The short commands (`/ban`, `/punish`, `/warn`, `/unban`, `/vanish`, `/guide`) can be switched off in the settings. If another plugin already uses one of these names, use `/sc ban ...` instead.

Every punishment gets a short ban ID such as `K7QX2M`. It is shown on the ban screen and to the player, `/unban` accepts it instead of the name, and `/appeal` only works with the player's own ID.

## Permissions

| Permission | Default | Meaning |
|---|---|---|
| `staffcore.admin` | op | Everything, including settings and the task editor |
| `staffcore.staff` | op | Punish, review messages, answer tickets |
| `staffcore.see` | op | Still sees punished players |
| `staffcore.notify` | op | Gets team notifications |
| `staffcore.voice` | op | Can moderate voice groups |
| `staffcore.vanish` | op | Can use /vanish and sees vanished staff |
| `staffcore.commands.all` | op | Sees commands that are hidden from players |
| `staffcore.ticket` | everyone | Can open tickets |
| `staffcore.teleport` | op | Can teleport to players from a ticket and pick a game mode |
| `staffcore.exempt` | nobody | Cannot be punished |

## Categories

Each category has a list of steps. The first step is used for the first offense, the second for the second, and the last one for every offense after that.

| Step type | What happens |
|---|---|
| `WARN` | The player gets a warning |
| `PLAYBAN` | The player keeps playing, hidden and restricted, for the given time |
| `SPECBAN` | The player may join but is held in spectator mode at a fixed place and can only write tickets |
| `BAN` | The player cannot join for the given time, or permanently |

Only offenses in the same category count. Punishments the team lifted do not count, and a category can let old offenses expire.

Anti-cheat plugins can run `playban apply <player> <category>` from the console.

## /admin: the admin panel

`/admin` opens a team member's own panel:

- **Inbox:** open tickets their rank may handle (`admin-panel.ticket-levels` sets the rank level per ticket kind, e.g. reports from 30, appeals from 50) and every player the anti-cheat marked.
- **Marked players** also stand right in the panel. A click takes the case: admin mode on, invisible, ghost mode, and off to the player; the team sees who took it. Shift-click removes the mark.
- **Admin mode** (staff mode), **vanish**, **ghost mode** and **ESP** as buttons.
- **My commands:** every command this member may use, from every plugin, by permission and rank level, with what it does; a click puts it into the chat.

**Active and inactive admins.** With `admin-mode.required` (the "Admin mode required" module, on by default) a team member is a normal player until they switch admin mode on in `/admin` (or `/admin on`). Team commands and admin items only work in admin mode; player commands like `/home` or `/ticket`, `/admin`, `/teamchat` and `/guide` always do. Admin mode stores the inventory safely and gives the admin items the member's rank may use; switching off gives everything back, the old game mode included. The console is never limited.

**Ghost mode** (`/admin ghost`, the echo shard in admin mode): creative and invisible, and walls are no obstacle. Next to a wall, floor (while sneaking) or ceiling the game switches to spectator for that moment, so you slide through and see the caves inside like a spectator; in the open you are back in creative. Switching off never leaves you inside a block.

Admin items cannot get their own tab in the creative inventory: those tabs come from the client, a server plugin cannot add one. They come from admin mode and `/tools` instead.

## Anti-cheat

**Points.** Every finding is measured, and the points grow smoothly with how far it lies outside what people can do: just over the human limit gives half a point or two, clearly over gives more, and at the point where it is plainly a cheat the full points. So a player 10 % too fast gets about 2 points, 50 % too fast about 10, twice as fast all 20. The limit is **25**:

| Finding | Points |
|---|---|
| Flying (hanging in the air, rising without help), walking on water, impossible head angles | 20 / 15 / 20 at once |
| No fall damage | 6 (6-10 blocks), 12 (10-20), 20 (more) |
| Speed | 1.5 to 20, by how far over the limit (15 %, 40 %, double) |
| Reach | 1 to 12, by how far beyond reach plus ping room |
| Kill aura | hits behind you 2-8, through walls 3, snap turns 1.5-3, two targets at once 1 |
| No knockback | 4-8 after four hits in a row without moving |
| Timer (too many movement packets) | 2 / 6 / 15 |
| Step | 4 (more than a slab), 10 (a full block) |
| Fast break | 1.5 / 4 / 8 by how much faster than tool, enchantments and haste allow |
| Nuker | 5-15 for many hard blocks per second, 2-6 for blocks out of reach |
| Scaffold / fast place | 2-5 for bridging under you without looking down, 3-10 for too many blocks per second |
| No slowdown while eating or drawing a bow | 3 / 6 |
| Auto totem | 4 (back in the off hand after 1 tick), 2 (2 ticks), 1 (3 ticks), 0 (slower) |
| Anchor and crystal macros | 1-3 by how fast place, charge and blow up follow each other |
| Auto clicker | 1 / 3 / 6 per second above the clicks limit |
| Aim | 1.5 when mouse turns have no common step in a fight (aim cheats turn by arbitrary amounts; never while zooming), 2 for the very same turn eight times in a row |
| Criticals | 3 for a critical hit while standing on the ground |
| Inventory while running | 2 for clicking in your own inventory while sprinting, three times |
| Fast bow | 4-8 for three and more full shots in one second |
| Sudden aim jump | up to 6: the mouse stood still, then jumped 40° and more in a single tick right before a hit, a block break or a placed block |
| Mining like a bot | up to 10: always dead on the middle of the block (people are a few degrees off) and the very same rhythm block after block |
| X-ray (share of diamond ore and ancient debris) and bot signs | mark only, they never reach the limit alone |

- **A clear finding (10 points and more)** shows a big warning ("Flying is not allowed here", points so far) and the same check waits `grace-seconds` (6) before it counts again, so a slow landing is not counted twice. Whoever carries on goes over the limit.
- **From 8 points** the player is marked for the team (`/admin`) and put on the **watch list**: their points count one and a half, every finding is reported at once, the team is told when they join. Admins put players on it by hand with `/anticheat watch <player>` or a right-click in `/admin`.
- **At 25 points** the player is **caught**: everybody around sees an animation (a black hole that pulls them up and swallows them, a giant mace falling from the sky, or a lightning cage; `animation: random`), then the next step of the **ladder**: PlayBan 5 minutes the first time, PlayBan 1 hour the second, then the cheating category. The points start fresh after that.
- Points fade by one every minute.
- **Bots:** more than 6 new accounts within 10 seconds turn on bot protection for 2 minutes (only known players can join). Writing the moment you join, the same message as several others, or a client that does not say what it is give bot points.
- Lag never counts: below `min-tps` (18) and above `max-ping` (300) movement is not judged. Creative, spectators, admin mode, vanished, frozen and punished players and `staffcore.anticheat.bypass` (nobody has it by default, ops neither) are not checked. Teleports, knockback, explosions, ice, water, ladders, cobwebs, slime, pistons, effects, elytra and vehicles are taken into account.
- **Recording:** every player's movement (position and where they look, every tick) and every action (hit, break, place, totem with its timing) is kept for the last ten seconds. A clear finding saves that as a clip (five per player), being caught also saves it to `anticheat-clips/`. `/anticheat replay <player> [1-5|now|stop]` or the button in `/check` plays it back: a figure with the player's head moves exactly as recorded, actions flash where they happened, and only you see it. Right-click in `/check` records the last ten seconds right now.
- Alerts are clickable and open `/check`; `/alerts` switches them off and on for yourself.
- `/anticheat` lists marked players, `/anticheat points <player>` shows the account per check, `watch`, `unwatch`, `reset` and `clear` do what they say. Limit, animation, watching, grace, speed limit, ping and clicks per second are in the module settings, every check can be switched off or weighted under `anticheat.checks`.

Test it with your own clients before you rely on it; if something is too strict or too lax, change the weight of that check.

**PlayBan both ways.** A PlayBan hides the player from everybody and everybody from the player (the team stays visible to them; `restrictions.hide-others`). When it is over the player is sent back: `playban.end-teleport` LAST (where they were before), SPAWN, HOME, LOCATION (`playban.end-location`) or NONE, also in Settings (shift-click there: the place where you stand).

## More team tools

- **`/check <player>`**: everything about one player on one page that updates every second. Ping, client, game mode, health, where they are, clicks per second right now, the anti-cheat account per check, the last findings and the last ten commands (passwords of `/login` and the like are hidden). Buttons: inventory, ender chest, freeze, watch, follow unseen (admin mode, ghost mode, to the player), reset points, punish, profile, block log of the last day, other accounts, teleport, prison for 15 minutes.
- **`/lag`**: TPS, milliseconds per tick, loaded chunks, entities, and the five busiest chunks with a click to go there. **`/cleanup [all]`** removes items, arrows and experience orbs lying around (without `all`, only those older than 30 seconds).
- **Announcements**: the messages under `announcer.messages` go round every few minutes (in order or random, with a quiet chime). **`/announce <text>`** shows a message with a title to everybody right now (colors yes, click actions no); `/announce next` sends the next one of the list.

## Modules

`/sc modules` (or the beacon in the main menu) shows every bigger system in one place. **Left-click** switches a module on or off: its commands are registered or removed at once, no restart, and the name is free for another plugin while it is off. **Right-click** opens its settings. From the console: `/sc modules` lists them, `/sc module homes off` switches one.

| Module | Commands | Settings |
|---|---|---|
| Homes | `/home [name]`, `/sethome [name]`, `/delhome <name>`, `/homes` (menu: click to go, shift-click to delete) | homes per player (`staffcore.homes.unlimited` has no limit), countdown, wait between uses |
| Teleport requests | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpacancel`, accept and deny are clickable | countdown, how long a request stays open, wait between requests |
| Money | `/money [player]` (`/bal`), `/pay`, `/baltop`, `/eco give\|take\|set` | starting money, currency symbol, symbol before or after |
| Spawn | `/spawn`, `/setspawn` | the spawn menu |
| Ranks | `/rank` | the rank menu |
| Anti-cheat | `/anticheat` (`/ac`) | auto-punish, category, speed limit, highest ping, clicks per second |
| Admin mode required | - | left-click: team members are normal players until `/admin` |
| Admin ESP | `/esp [players\|chests\|all\|off]`, the ESP spyglass in staff mode | player range, chest range, most chests |
| Block log | `/blocklog` | days kept, largest radius |
| Admin commands | `/tpadmin`, `/freeze`, `/invsee`, `/fly`, `/heal`, `/god`, ... | - |
| Short commands | `/ban`, `/mute`, `/warn`, `/vanish`, ... | - |
| Lag tools | `/lag`, `/cleanup` | - |
| Announcements | `/announce` | how often, random order, sound |
| Prison, fake bases, trolls | `/prison`, `/fakebase`, `/troll` | prison key time; fake base minutes and maximum |

Teleports from homes and requests count down while you stand still; moving cancels them. Frozen, cuffed, jailed or punished players cannot use them, and vanished staff cannot be found with `/tpa`.

**Money** is kept in `money.yml`. With Vault installed and no other economy, StaffCore offers its balances to Vault, so shops and other plugins use them; if another economy is already there, StaffCore keeps out of its way. The gift tasks of a PlayBan use it as well.

**Admin ESP** shows chests, barrels, shulker boxes and other containers as glowing outlines through walls (colored by kind), and every player with a name tag through walls with health, distance and the item in hand (vanished staff marked `[V]`). Only the team member who switched it on sees it, nobody else gets any of it. The spyglass in staff mode switches off, players, chests, both; leaving staff mode switches it off.

## Ranks

Owner, Co-Owner, Admin, Dev, Mod, Supporter and Player come ready-made (`ranks.yml`, or `/rank menu`). Each rank has a level, a prefix shown in chat and the tab list, and permissions it gives (`staffcore.*` and `*` are expanded).

Command levels say from which level a command may be used, ops included, so OP alone is no longer enough: by default `/gamemode` needs 70, `/give` 80, `/execute` 90 and `/op` 100. A rule can also name part of a command, like `sc reload`. The levels start once someone holds the highest rank, which an op claims once with `/rank setup`, so nobody locks themselves out. Nobody can give, change or remove a rank at or above their own level. Command blocks are not covered, so set `enable-command-block=false`.

The rules hold on every way a command can be run: below the level the command's own permission (for example `minecraft.command.gamemode`) is taken away as well, so a menu, an alias or another plugin running the command for the player does not get around it (`enforce-permissions: false` in `ranks.yml` turns that off). Only the highest rank may change command rules, the default rank's level and wildcard permissions (`*`, `x.*`); everybody else can only hand out permissions they have themselves. Rank prefixes take colors and text styles, no click or hover actions.

| Command | What it does | Permission |
|---|---|---|
| `/rank set <player> <rank>` | Give a rank below your own level | `staffcore.ranks` |
| `/rank remove <player>`, `info`, `list` | Take away, look up, list | `staffcore.ranks` |
| `/rank setup` | Claim the highest rank once, as an op | `staffcore.ranks` |
| `/rank menu` | Edit ranks, permissions, players and command levels | `staffcore.ranks` |

## Admin commands and items

| Command | What it does | Permission |
|---|---|---|
| `/tpadmin <player>` | Teleport there invisible; `/tpback` returns | `staffcore.tpadmin` |
| `/tphere <player>` | Bring a player to you | `staffcore.tpadmin` |
| `/freeze <player>` | Freeze in place; logging out is reported | `staffcore.freeze` |
| `/invsee`, `/endersee <player>` | Look into inventories; edit with `staffcore.invsee.edit` | `staffcore.invsee` |
| `/teamchat [text]` | Team-only chat, toggle or one message | `staffcore.teamchat` |
| `/clearchat` | Clear the chat for players | `staffcore.clearchat` |
| `/fly`, `/heal`, `/god [player]` | For staff | `staffcore.fly`, `.heal`, `.god` |
| `/tools` | Admin items | `staffcore.tools` |
| `/prison <player> [time] [reason]` | Wall the player into a bedrock cell; `/unprison` lets them out | `staffcore.prison` |
| `/troll <player> [joke]` | Harmless jokes, menu without a joke | `staffcore.troll` |
| `/stafflog [player]` | Team protocol: who did what | `staffcore.stafflog` |
| `/fakebase [here\|<player>] [1-8]` | Bait base deep underground against ESP and x-ray | `staffcore.fakebase` |

**Prison.** The player is walled in with bedrock where they stand. Their inventory turns into grey lock panes: no items, no scrolling through the hotbar, no opening inventories, no commands except `/ticket` and `/appeal`. The blocks of the cell and the items are stored in `prisons.yml` and come back exactly as they were on release, also after a restart; someone released while offline gets their items on the next join. Nobody can break the cage.

**Trolls.** Chicken rain (gone after 8 seconds, no drops), launch (no fall damage), creeper hiss, fake explosion, fake OP message, spin, lightning effect, demo screen, sneeze. Nothing hurts, nothing is taken, nobody else sees it. Protected players cannot be trolled.

**Fake base.** `/fakebase Steve` hides a base deep underground, 40 to 70 blocks away from Steve, where nobody finds it without seeing through walls. The room is dug out and filled with one of eight designs (deepslate vault, stone bunker, blackstone hideout, copper lab, mud brick cellar, quartz storage, tuff hall, obsidian stash): a stand of double chests rising one block back and one block up per row, or chest walls or rows, plus furnaces, crafting table, enchanting table, anvil, beds and an ender chest. Two fake players with name tags walk around in it (real player figures on 1.21.9 and newer, dressed armor stands before). The chests look stocked, but nothing can be taken out, and nothing in the base can be broken, blown up or flooded. The team is told when a player digs towards it underground (10 blocks away), walks in or opens a chest, with a click to watch. After `fakebase.minutes` (30) the base is gone and everything, including chests that were there before and their contents, looks like before; also on shutdown and after a crash. `/fakebase here [1-8]` builds it where you stand, `/fakebase at <x> <y> <z>` works from the console, `/fakebase list`, `tp`, `remove <id|all>`.

**Team protocol.** Punishments, pardons, prison, trolls, failed knockback tests, opened bait chests and every command a team member types (private messages left out, `staff-log.commands`) are written to `blocklog.db`. `/stafflog Steve` shows what was done by or to Steve.

The items from `/tools`, each with its own permission `staffcore.tool.<name>`: bannhammer (a hit opens the punish menu), freeze wand (aim and right-click), handcuffs (the player follows you and cannot act), inspector (right-click shows the profile), teleport compass (jump to anyone, invisible), vanish cloak, x-ray glasses (ores within 20 blocks glow through walls for 15 seconds, only for you), knockback stick (a hit pushes like a normal hit without damage and tells you how far the player really moved: barely moving points to an anti-knockback cheat), spectate eye (look through a player's eyes, sneak to come back), prison key (right-click: into the prison for `prison.tool-time`, again: out), troll wand (opens the troll menu) and bait chest (right-click a chest to make it bait: the team is told the moment a player opens it, good against x-rayers and thieves).

Admin items never drop on death and cannot be put into chests, item frames or armor stands, not even with the number keys.

## Block log and replay

Every broken, placed, burned or blown-up block (TNT is put down to whoever lit it) and every item taken out of or put into a chest is written to `blocklog.db`, an SQLite file. Paper ships the driver, nothing needs to be installed. Writing happens in the background in batches, entries older than `block-log.keep-days` are removed on start.

| Command | What it does | Permission |
|---|---|---|
| `/blocklog near [radius] [time]` | Changes around you (also `/log`, `/bl`) | `staffcore.blocklog` |
| `/blocklog player <name> [time]` | Everything one player did | `staffcore.blocklog` |
| `/blocklog rollback <name> [time] [radius]` | Undo a player's blocks and chest takings | `staffcore.blocklog.rollback` |
| `/blocklog restore <name> [time] [radius]` | Redo them | `staffcore.blocklog.rollback` |
| `/blocklog replay [radius] [time] [name]` | Watch the changes happen again, with names, only for you | `staffcore.blocklog` |

A rollback only touches blocks that still look like right after the logged change, so things other players built there later stay. Big rollbacks run in steps of 2,000 blocks per tick. From the console a player has to be named, so the whole server cannot be rolled back by accident.

The replay shows the area as it was, then plays every change with the player's name floating above the block, with pause, slower, faster and stop in chat and a progress bar. Player movement is not recorded, only blocks and chests. The block-log brush from `/tools` shows the history of the block or chest you click.

## Staff mode

`/staffmode` stores your inventory safely (also on disk, so a crash cannot lose it), turns you invisible, lets you fly and fills your inventory with every admin item you may use. The barrier item or `/staffmode` again gives everything back, and so does leaving the server. Things you picked up while in staff mode (from a chest or someone's inventory) are kept. Admin items cannot be dropped or put into chests. The warn stick warns a player with a hit and takes the newest warning back with a right-click.

## Hidden commands

Players without `staffcore.commands.all` do not see team commands in their suggestions, and the commands listed under `command-hiding` (by default `plugins`, `pl`, `version` and similar) are hidden and answer like an unknown command. Forms like `bukkit:plugins` are hidden too. The list can be changed in Settings.

## Tickets

Any player can open a ticket and picks a reason (report, question, bug, appeal or your own).

- The team sees all tickets in `/ticket show`, with filters for status and reason.
- Opening a ticket shows what is already known about the player. Accepting it puts the ticket "in progress".
- While a ticket is in progress, what the player types goes only to the team, and the player does not read the public chat. The chat can be paused, and it pauses on its own when the team member leaves.
- The team member works in a ticket window: every message is an entry that can be starred, plus buttons for writing, quick replies, team-only remarks, adding more players (one shared chat or separate chats), teleporting, a voice invite and finishing.
- To finish, the team member decides for every player: a punishment by category, or "only involved". Then the ticket is closed and the punishments are carried out.
- Closed tickets stay readable with the whole conversation, stars, remarks and results, and show up in the profile of every player who was part of them. Only admins can still add remarks to a closed ticket.

Extras: new tickets can be posted to a Discord channel (webhook), the team is reminded of tickets nobody picked up, and the profile shows other players who joined from the same address. Addresses are stored as a fingerprint, not in plain text.

## Voice groups

With the plugin Simple Voice Chat installed, `/voice` shows the team every voice group. Players only need the normal voice chat mod. The buttons are in StaffCore's own menu, because a server plugin cannot change the mod's screens.

- Join a group visibly or unseen. Unseen means players do not see the team member in the group, but talking and listening work. Each team member can set which way is the default.
- Per player: remove from the group, mute, "hears nothing", ban from the group. Mute and "hears nothing" end when the player leaves the group.
- Per group: close it, set or remove a password, and show a password in chat where a click copies it. StaffCore only knows passwords set through its menu.
- Whoever created a group can kick and ban in it with `/voice manage`.
- Players report a group with `/voice report`, which opens a ticket.
- The "Voice" button in a ticket creates a private voice group and moves everyone into it. Players are muted until they click to speak.

## Ban screen and Discord

Under Settings -> Ban screen you can write your own lines for the screen a banned player sees and set your Discord invite link. The link is also shown to spectator-banned players.

## Tasks

| Type | What the player does |
|---|---|
| `STAFF_MESSAGE` | Writes an apology to the team |
| `NICE_MESSAGE` | Writes a kind message to another player |
| `GIFT_MONEY` | Gives money to another player (needs Vault) |
| `DONATE_ITEMS` | Hands in items from the inventory |
| `QUIZ` | Answers your own questions about the rules |

Messages are only delivered and only count after a team member approved them in the menu.

## Files

- `config.yml` - settings (also editable in the menu)
- `categories.yml` - categories and their steps (also editable in the menu)
- `tasks.yml` - tasks (also editable in the menu)
- `lang/en.yml`, `lang/de.yml` - all texts; copy one to add your own language
- `data.yml` - punishments, bans, history, notes and open reviews
- `tickets.yml` - all tickets with their conversations
- `ranks.yml` - ranks, who has which, command levels
- `prisons.yml`, `prisons-return.yml` - prison cells and items waiting for their owner
- `blocklog.db` - block log and team protocol (SQLite)
- `homes.yml`, `money.yml` - homes and balances
- `anticheat.yml` - marked players
- `staffmode-gamemodes.yml` - the game mode before admin mode, while it is on
- `fakebases.yml` - only while a fake base stands: the blocks to put back

## Build

```
mvn package
```

The jar is written to `target/`.
