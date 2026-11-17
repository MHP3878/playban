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

## Ranks

Owner, Co-Owner, Admin, Dev, Mod, Supporter and Player come ready-made (`ranks.yml`, or `/rank menu`). Each rank has a level, a prefix shown in chat and the tab list, and permissions it gives (`staffcore.*` and `*` are expanded).

Command levels say from which level a command may be used, ops included, so OP alone is no longer enough: by default `/gamemode` needs 70, `/give` 80, `/execute` 90 and `/op` 100. A rule can also name part of a command, like `sc reload`. The levels start once someone holds the highest rank, which an op claims once with `/rank setup`, so nobody locks themselves out. Nobody can give, change or remove a rank at or above their own level. Command blocks are not covered, so set `enable-command-block=false`.

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

The items from `/tools`, each with its own permission `staffcore.tool.<name>`: bannhammer (a hit opens the punish menu), freeze wand (aim and right-click), handcuffs (the player follows you and cannot act), inspector (right-click shows the profile), teleport compass (jump to anyone, invisible), vanish cloak and x-ray glasses (ores within 20 blocks glow through walls for 15 seconds, only for you).

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

## Build

```
mvn package
```

The jar is written to `target/`.
