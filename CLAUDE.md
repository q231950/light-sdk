# CLAUDE.md

This is a fork of Light Phone's `light-sdk` scaffolding. Everything upstream (`sdk/*`,
`plugin`, `lint-rules`, `builder`, `examples`) is **theirs** — the work that happens here
is in two modules we own:

| Module | Ours? | Contents |
|--------|-------|----------|
| `:tool` | yes | The `#flamingo chess` Light Phone III tool, package `dev.neoneon.flamingo` |
| `:chesskit` | yes | A pure-Kotlin/JVM chess engine — board, legality, FEN/SAN/LAN/PGN parsers |
| `:sdk:*`, `:plugin`, `:lint-rules`, `:builder` | no | Upstream scaffolding; avoid changing |

ALWAYS call the app `#flamingo chess`, especially in user facing copy.

`#flamingo chess` spans three repositories, and a change to the wire contract is a
three-repo change:

| Repo | What lives there |
|------|------------------|
| `q231950/light-sdk` (this one) | The Light Phone III tool and `:chesskit` |
| `q231950/neoneon` | The Vapor backend at `https://neoneon.dev` — `docs/flamingo-api.md` is the contract |
| `q231950/flamingo` | iOS: iMessage extension, companion app, `FlamingoChessCore` |

## Git

Use [Conventional Commits](https://www.conventionalcommits.org/). Work in this repo is
scoped `(tool)` — e.g. `feat(tool): highlight the king in check`.

## Build and test

```bash
./gradlew check          # every module must compile and every test must pass
./gradlew :tool:test     # tool unit tests only
./gradlew :chesskit:test # engine tests (incl. perft)
./gradlew :tool:assembleDebug
```

`compileSdk 36`, `minSdk 34`, JVM target 17 (set in the root `build.gradle.kts`).
Debug and release both sign with the shared `lightsdk-dev` keystore.

Test on a real Light Phone III, or on an Android emulator running the LightOS emulator app
as a system app (see `docs/system_app`) — push notifications and special permissions only
work there. An LPIII-ish AVD is 1080×1240, 3.92", API 34, no Play Services.

## Platform constraints

LightOS deliberately restricts which Android APIs and third-party libraries a tool may
use, and `:lint-rules` enforces part of that (`RestrictedApi` is an error in `:tool`).
Kotlin + Compose + Coroutines + MVVM only. Before reaching for a library, check that it is
allowed — the safe set is what `:sdk:client` already exposes plus what `tool/build.gradle.kts`
already declares (Ktor websockets, Room/KSP, lifecycle-viewmodel-compose, Compose icons).

LightOS has no system navigation. Move between screens with `navigateTo` from `LightScreen`;
the SDK supplies the back button. Screens come in `LightScreen` / `LightViewModel` pairs.

## The tool

`tool/src/main/kotlin/dev/neoneon/flamingo/`

| File | Responsibility |
|------|----------------|
| `ToolEntryPoint` | `@EntryPoint` — LightOS registration data and push notifications |
| `GamesListScreen` / `GameListRow` | The tool's home: the player's games and what their status means |
| `CreateGameScreen` / `JoinByPhraseScreen` / `LightCodeInput` | Invite-code create and join, OTP-style per-letter entry |
| `SharePhrase` | Sharing the invite code from inside a game |
| `GameView` / `ChessBoard` | The board, its ViewModel, and rendering (check highlight, last-move dot-and-arrow) |
| `GameActions` / `GameOutcome` | Draw offer/answer/decline, resign, and how a game ended |
| `ReplayLog` | Move history, numbered by half-move to match the backend |
| `FlamingoApi` / `FlamingoModels` | Ktor HTTP client against `https://neoneon.dev/flamingo` |
| `LiveTransport` (port) / `KtorLiveTransport` (adapter) | The live WebSocket |
| `PlayerIdentity` | One player ID per installation, in DataStore |
| `SettingsScreen` / `LastMoveVisibility` | Settings, incl. last-move marking (`Hidden` / `Latest` / `OpponentOnly`) |
| `InfoScreen` | Legal docs and move actions, reached from the bottom bar |

Keep the transport behind the `LiveTransport` port — `KtorLiveTransport` is the only thing
that should know about Ktor websockets. The iOS side mirrors this split
(`LiveTransport` / `URLSessionLiveTransport`), so the two clients stay comparable.

## Wire contract

Read `neoneon/docs/flamingo-api.md` before touching `FlamingoApi` or `FlamingoModels`.

Live frames (`LiveAction`), over `wss://neoneon.dev/flamingo/games/<gameId>/socket?pid=<playerId>`:

```json
{ "intent": "move", "player": "<uuid>", "lan": "e2e4", "fen": "<pre-move FEN>", "n": 3 }
```

`intent` ∈ `move | offerDraw | acceptDraw | declineDraw | resign`. The gameId is in the
path and never repeated in the frame. `lan` is present only for moves; `fen` and `n`
travel on every frame — draw/resign included — so the live path writes the same server
history as the HTTP one. For a draw/resign frame, `fen` is the current position and `n` is
the half-move the actor would have played next.

## Gotchas

- **`fen` is the position a move was played *from*, not the one it produced.** That holds
  for the socket frame, the URL contract, and `MoveDTO.fenAfter` (a misnomer kept for wire
  compatibility). The receiver seeds from `fen` and replays `lan` on top.
- **Compare player IDs case-insensitively** (`samePlayer`). This client mints lowercase
  `UUID.randomUUID()` strings; the server round-trips them through a Swift `UUID` and
  echoes them back uppercase. A plain `==` silently resolved every player to black.
- **One identity per install, either color.** `PlayerIdentityStore` reuses the legacy
  `FLAMINGO_WHITE_PLAYER_ID` value when present, so games created back when a local game
  was always white aren't orphaned.
- Invite codes are five letters from an alphabet without `I`, `O`, `Q`, `U`, but parsing is
  lenient — normalize entry (uppercase, ASCII letters, truncate), never reject a letter a
  user might read off a screen.
- A game action shares a `moveNumber` with the move answering it; order history by
  `moveNumber` then timestamp. `ReplayLog` numbers entries by half-move to match.
- The socket carries live actions only, with no history replay — re-sync over
  `GET /flamingo/games/:id` before reconnecting after a drop.
- `LiveAction`'s KDoc on the iOS side claims `fen`/`n` are move-only; that comment is
  stale, the behaviour is as described above.
