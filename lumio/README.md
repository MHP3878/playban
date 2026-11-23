# Lumio

A clean, useful everyday client mod for Fabric: smooth design without pixel fonts, a HUD you place
yourself, a radial menu you fill yourself, music from Spotify and other players, chat mentions, and
extras on servers that support it. No cheats.

| Minecraft | File |
|---|---|
| 1.21.4 | `dist/lumio-<version>+1.21.4.jar` |
| 1.21.6 – 1.21.8 | `dist/lumio-<version>+1.21.8.jar` |
| 1.21.9 – 1.21.10 | `dist/lumio-<version>+1.21.10.jar` |
| 1.21.11 | `dist/lumio-<version>+1.21.11.jar` |

Needs Fabric Loader and Fabric API.

## What it does

**Menu (Right Shift, or `/lumio`).** General (six colour themes, card side, sounds), HUD, radial menu,
chat, about. Everything is drawn in the Inter font with soft rounded panels.

**HUD.** Coordinates with facing, FPS, ping, clock, armour with durability, effects, keys with clicks
per second, important items (totems, pearls, golden apples), server info and music. "Edit HUD" shows all
of them: drag them where you want (they snap to the middle), right-click switches one on or off, the
mouse wheel changes its size. Positions are kept as a share of the screen, so they stay in place when the
window or GUI scale changes.

**Music.** The music element shows what is playing: cover, title, artist and a progress bar, green for
Spotify. It works with any player the computer knows, without logging in anywhere:

- Windows: the media session Windows shows in its volume overlay (Spotify, browsers, media players),
  read by a small PowerShell script Lumio writes to `.minecraft/lumio/media.ps1` and that ends with the game;
- macOS: Spotify and Apple Music via AppleScript (macOS asks once whether Minecraft may do that);
- Linux: every MPRIS player via `playerctl` (install it if the element stays empty).

Keys for pause, next and previous can be set under Options → Controls → Lumio (none by default; the
keyboard's own media keys work anyway). What is playing stays on your computer and is never sent to a
server. The reader only runs while the element is on.

**Radial menu (hold G).** Up to eight fields in a circle; point at one and let go of the key. A field
runs a command, writes a chat message, or opens a next page. In the menu's radial menu tab you make your
own: name, picture (an item), what it does; a server can suggest one, which you can take over and change.

**Chat.** Messages that name you light up in the theme colour, get a coloured bar with a hint on hover
and a soft sound. Your own words (event, shop, ...) work the same way. Your own messages and messages
about you ("... joined the game") are left alone.

**Cards.** A server can send a request card (for example a teleport request) that you answer with one
key: **Y** accepts, **N** denies. Notice cards slide in at the bottom, away from Minecraft's own toasts.

**Multiplayer list.** A server that supports Lumio can show its colours behind its entry, up to four tags
next to its name and a bar of how full it is. It puts this as an invisible part into its description,
so everyone else sees the usual list.

## On servers with StaffCore and ModCheck

- Teleport requests, announcements and (for the team) new tickets come as cards.
- The HUD shows balance and time left in prison, for the team open tickets and vanish.
- The radial menu suggestion is made from the server's modules: home, spawn, accept a teleport,
  `/totem` (a totem from the inventory into the off hand), ticket, money.
- The multiplayer list shows the server's colours and tags (`lumio.server-list` in StaffCore's config).

## Fair play, openly

A server that supports Lumio greets it with a random word. Lumio answers with its version and the mods
Fabric Loader loaded: id, name, version and a SHA-256 fingerprint of each jar. Nothing else on the
computer is read. This is said in the mod's description too.

When the server asks for it (ModCheck's injection check), Lumio also checks the running game for code
put in from outside, at the start and every few seconds while playing: classes defined without a file,
threads that start in memory belonging to no DLL (Windows), Java agents, foreign libraries, and
programs whose names are on the server's list of injectors. It tells the server only what it found,
by file name, never folders; nothing else on the computer is read. Only this game's own process is
looked at, apart from the names of running programs, which are compared with that list on your
computer (only a match is told).

ModCheck on the server checks the list against what it saw itself (channels, the hidden sign): a mod
seen there but missing in the list means the list was forged. The server owner decides with
`/modcheck lumio off|optional|required` whether Lumio is needed (Bedrock players via Geyser and
`modcheck.bypass` are always free).

## The protocol (for server plugins)

All messages are UTF-8 JSON on plugin channels.

| Channel | Direction | Content |
|---|---|---|
| `lumio:hello` | server → client | `{"v":1,"nonce":"…","server":"…","latest":"0.6.0","download":"https://…","integrity":true,"watch":["vape"]}` |
| `lumio:report` | client → server | `{"v":1,"nonce":"…","part":1,"parts":1,"lumio":"…","minecraft":"…","loader":"…","mods":[{"id","name","version","sha256","in"}]}` (split into parts below 28 KB) |
| `lumio:prompt` | server → client | `{"id":"…","title":"…","text":"…","accept_command":"tpaccept Max","deny_command":"tpdeny Max","seconds":60}` |
| `lumio:answer` | client → server | `{"id":"…","accept":true}` (only when the card has no commands) |
| `lumio:toast` | server → client | `{"title":"…","text":"…","kind":"info/success/warn/error","seconds":6}` |
| `lumio:hud` | server → client | `{"clear":true,"lines":[{"id":"money","label":"Balance","value":"$100"}]}` (at most 8 lines) |
| `lumio:integrity` | client → server | `{"v":1,"nonce":"…","seq":3,"checks":["agents","attach","native","processes","code"],"findings":[{"kind":"code","strong":true,"what":"gg.vape.Cheat","name":"Vape"}]}` (on start, on news, every 30 s) |
| `lumio:wheel` | server → client | `{"entries":[{"name":"Home","icon":"minecraft:red_bed","type":"command","value":"home"},{"name":"More","type":"page","page":[…]}]}` |

The multiplayer list reads a part of the server description whose `insertion` is
`lumio:{"v":1,"c":["#ff5f6d","#ffc371"],"tags":["Survival","PvP"]}`.

Everything from the server is data: texts are cleaned and cut, links must be `https://`, commands run
as the player with the player's own permissions.

## Building

`./gradlew build` (Java 21). The shared code is in `src/`, what differs between Minecraft versions in
`versions/<version>/src/` (`Compat.java`, `BaseScreen.java`, the server list mixin). The jars land in
`dist/`.

Fonts: Inter (SIL Open Font License, `LICENSE-Inter.txt`).
