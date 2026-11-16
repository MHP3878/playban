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
| `/playban unban <player>` | Lift a ban | `playban.staff` |
| `/playban pardon <player>` | End a PlayBan | `playban.staff` |
| `/playban addtime` / `removetime <player> <time>` | Change the remaining time | `playban.staff` |
| `/playban info <player>`, `list`, `history <player>` | Look things up | `playban.staff` |
| `/playban tasks` | Own tasks (for punished team members) | - |
| `/playban lang <code>` | Switch language (`en`, `de`) | `playban.admin` |
| `/playban reload` | Reload config, tasks and texts | `playban.admin` |
| `/ticket <message>` | Punished player writes to the team | - |

Times look like `30m`, `2h`, `1d 12h`.

## Permissions

| Permission | Default | Meaning |
|---|---|---|
| `playban.admin` | op | Everything, including settings and the task editor |
| `playban.staff` | op | Punish, review messages, answer tickets |
| `playban.see` | op | Still sees punished players |
| `playban.notify` | op | Gets team notifications |
| `playban.exempt` | nobody | Cannot be punished |

## Categories

Each category has a list of steps. The first step is used for the first offense, the second for the second, and the last one for every offense after that.

| Step type | What happens |
|---|---|
| `WARN` | The player gets a warning |
| `PLAYBAN` | The player keeps playing, hidden and restricted, for the given time |
| `BAN` | The player cannot join for the given time, or permanently |

Only offenses in the same category count. Punishments the team lifted do not count, and a category can let old offenses expire.

Anti-cheat plugins can run `playban apply <player> <category>` from the console.

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
- `data.yml` - punishments, bans, history, open reviews and tickets

## Build

```
mvn package
```

The jar is written to `target/`.
