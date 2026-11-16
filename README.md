# PlayBan

Punish players without banning them. A punished player stays on the server, but:

- other players cannot see them (they still see everyone else)
- they cannot break, place, interact, attack, drop or pick up anything
- they cannot chat and can only use the commands you allow, plus `/ticket`
- they get tasks, and every finished task shortens the punishment

Everything is controlled through in-game menus: punishing, reviewing messages, tickets, the task editor and all settings.

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
| `/playban punish <player> <time> [reason]` | Punish a player | `playban.staff` |
| `/playban pardon <player>` | End a punishment | `playban.staff` |
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
- `tasks.yml` - tasks (also editable in the menu)
- `lang/en.yml`, `lang/de.yml` - all texts; copy one to add your own language
- `data.yml` - punishments, history, open reviews and tickets

## Build

```
mvn package
```

The jar is written to `target/`.
