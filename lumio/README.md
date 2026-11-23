# Lumio

A clean, useful everyday client mod for Fabric. This is stage 1: the base the rest builds on.

| Minecraft | File |
|---|---|
| 1.21.4 | `dist/lumio-<version>+1.21.4.jar` |
| 1.21.6 – 1.21.8 | `dist/lumio-<version>+1.21.8.jar` |
| 1.21.9 – 1.21.10 | `dist/lumio-<version>+1.21.10.jar` |
| 1.21.11 | `dist/lumio-<version>+1.21.11.jar` |

Needs Fabric Loader and Fabric API.

## What it does now

- **Cards instead of chat buttons.** A server can send a request card (for example a TPA request) that
  the player answers with one key: **Y** accepts, **N** denies (both can be changed under
  Options → Controls → Lumio). Notice cards slide in at the bottom right, away from Minecraft's own
  toasts at the top right.
- **Update hint.** A server tells Lumio which version is the newest; an older Lumio shows a card and a
  download link in chat.
- `/lumio` shows the version and whether the server supports Lumio; `/lumio demo` shows the cards.

## Fair play, openly

A server that supports Lumio greets it with a random word. Lumio answers with its version and the mods
Fabric Loader loaded: id, name, version and a SHA-256 fingerprint of each jar. Nothing else on the
computer is read. This is said in the mod's description too.

ModCheck on the server checks the list against what it saw itself (channels, the hidden sign): a mod
seen there but missing in the list means the list was forged. In ModCheck's `lumio.mode: required`
only players with Lumio may play (Bedrock players via Geyser and `modcheck.bypass` are always free).

## The protocol (for server plugins)

All messages are UTF-8 JSON on plugin channels.

| Channel | Direction | Content |
|---|---|---|
| `lumio:hello` | server → client | `{"v":1,"nonce":"…","server":"…","latest":"0.2.0","download":"https://…"}` |
| `lumio:report` | client → server | `{"v":1,"nonce":"…","part":1,"parts":1,"lumio":"…","minecraft":"…","loader":"…","mods":[{"id","name","version","sha256","in"}]}` (split into parts below 28 KB) |
| `lumio:prompt` | server → client | `{"id":"…","title":"…","text":"…","accept_command":"tpaccept Max","deny_command":"tpdeny Max","seconds":60}` |
| `lumio:answer` | client → server | `{"id":"…","accept":true}` (only when the card has no commands) |
| `lumio:toast` | server → client | `{"title":"…","text":"…","kind":"info/success/warn/error","seconds":6}` |

Everything from the server is data: texts are cleaned and cut, links must be `https://`, commands run
as the player with the player's own permissions.

## Building

`./gradlew build` (Java 21). The shared code is in `src/`, what differs between Minecraft versions in
`versions/<version>/src/` (`Compat.java`). The jars land in `dist/`.
