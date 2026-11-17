# PlayBan

Punish players without banning them. A punished player stays on the server, but:

- other players cannot see them (they still see everyone else)
- they cannot break, place, interact, attack, drop or pick up anything
- they cannot chat and can only use the commands you allow, plus `/ticket`
- they get tasks, and every finished task shortens the punishment

PlayBan also hands out warnings and real bans. You define categories such as X-Ray, Cheating or Spam, each with its own ladder: what happens on the first, second, third offense. The team only picks the category, the plugin works out the punishment.

Everything is controlled through in-game menus: punishing, categories, reviewing messages, tickets, the task editor and all settings.

## Requirements

- Paper 1.21 or newer, Java 21
- Optional: Vault plus an economy plugin for the "give money" task

## Install

1. Put `PlayBan-<version>.jar` into the `plugins` folder and restart the server.
2. Run `/playban menu`.

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/playban` or `/pb` | Team: main menu. Punished player: own tasks | - |
| `/playban <player>` | Pick a category for a player in a menu | `playban.staff` |
| `/playban apply <player> <category>` | Punish by category, also works from the console | `playban.staff` |
| `/playban punish <player> <time> [reason]` | PlayBan with a duration of your choice | `playban.staff` |
| `/playban warn <player> [reason]` | Warn a player | `playban.staff` |
| `/playban ban <player> <time or perm> [reason]` | Real ban | `playban.staff` |
| `/playban specban <player> <time or perm> [reason]` | Spectator ban: may join, but is stuck and can only write tickets | `playban.staff` |
| `/playban unban <player>` | Lift a ban or spectator ban | `playban.staff` |
| `/playban note <player> <text>` | Write a note about a player | `playban.staff` |
| `/playban profile <player>` | Notes and punishments of a player | `playban.staff` |
| `/playban pardon <player>` | End a PlayBan | `playban.staff` |
| `/playban addtime` / `removetime <player> <time>` | Change the remaining time | `playban.staff` |
| `/playban info <player>`, `list`, `history <player>` | Look things up | `playban.staff` |
| `/playban tasks` | Own tasks (for punished team members) | - |
| `/playban lang <code>` | Switch language (`en`, `de`) | `playban.admin` |
| `/playban reload` | Reload config, tasks and texts | `playban.admin` |
| `/ticket` | Open a ticket: pick a reason, then describe it | `playban.ticket` |
| `/ticket <message>` | Open a ticket directly | `playban.ticket` |
| `/ticket show [reason]` | Ticket overview for the team, optionally filtered | `playban.staff` |
| `/ticket open <number>` | Open one ticket | `playban.staff` |
| `/ticket stats` | Who handled how many tickets | `playban.staff` |
| `/ticket back` | Return after teleporting from a ticket | `playban.staff` |

Times look like `30m`, `2h`, `1d 12h`.

## Permissions

| Permission | Default | Meaning |
|---|---|---|
| `playban.admin` | op | Everything, including settings and the task editor |
| `playban.staff` | op | Punish, review messages, answer tickets |
| `playban.see` | op | Still sees punished players |
| `playban.notify` | op | Gets team notifications |
| `playban.ticket` | everyone | Can open tickets |
| `playban.teleport` | op | Can teleport to players from a ticket and pick a game mode |
| `playban.exempt` | nobody | Cannot be punished |

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

## Tickets

Any player can open a ticket and picks a reason (report, question, bug, appeal or your own).

- The team sees all tickets in `/ticket show`, with filters for status and reason.
- Opening a ticket shows what is already known about the player. Accepting it puts the ticket "in progress".
- While a ticket is in progress, what the player types goes only to the team, and the player does not read the public chat. The chat can be paused, and it pauses on its own when the team member leaves.
- The team member works in a ticket window: every message is an entry that can be starred, plus buttons for writing, quick replies, team-only remarks, adding more players (one shared chat or separate chats), teleporting, a voice invite and finishing.
- To finish, the team member decides for every player: a punishment by category, or "only involved". Then the ticket is closed and the punishments are carried out.
- Closed tickets stay readable with the whole conversation, stars, remarks and results, and show up in the profile of every player who was part of them. Only admins can still add remarks to a closed ticket.

Extras: new tickets can be posted to a Discord channel (webhook), the team is reminded of tickets nobody picked up, and the profile shows other players who joined from the same address. Addresses are stored as a fingerprint, not in plain text.

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
