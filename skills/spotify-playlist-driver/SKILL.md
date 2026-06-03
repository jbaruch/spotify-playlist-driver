---
name: spotify-playlist-driver
description: |
  Use this when the user wants to bulk-edit a Spotify playlist — add tracks,
  remove tracks, dedupe, verify contents — and they have open.spotify.com
  open in a logged-in tab. Covers search-and-add, remove-by-uid, list-contents,
  duplicate-removal, and the rate-limit + human-pacing patterns that Spotify
  enforces. Read this BEFORE attempting any direct GraphQL calls or
  scripting against the playlist — the rediscovery cost without this skill
  is significant (we know).
allowed-tools: bash, read_file
---

# spotify-playlist-driver

## What this skill does

Bulk-edit a Spotify playlist (add, remove, dedupe, verify) from a logged-in `open.spotify.com` tab, by driving the web player's private GraphQL endpoint at `api-partner.spotify.com/pathfinder/v2/query` with the page's own bearer token. Ships a `spotify-playlist` shell command that does the heavy lifting — search-and-add, remove by uid or fuzzy filter, paginated listing, dedupe, and human-paced verification — so an agent doesn't have to re-derive the persisted-query hashes, the bearer-capture hook, or the rate-limit pacing rules every session.

## Prerequisites

- The user has **open.spotify.com open in a tab and is logged in.** The skill auto-discovers the tab via `browser.findTab({ domain: 'open.spotify.com' })`. If no tab is open, `spotify-playlist` will say so and exit.
- A bearer token will be extracted from the page. The token belongs to the page's session — we do not register an OAuth app and do not call `api.spotify.com/v1/*` (that endpoint 429s the web bearer instantly).
- Playlist ID = the 22-char base62 segment from `https://open.spotify.com/playlist/<id>`.

## Commands

Run `spotify-playlist <subcommand> ...`. All subcommands accept `--help`.

| Subcommand                                | Purpose                                                                                  |
| ----------------------------------------- | ---------------------------------------------------------------------------------------- |
| `bearer`                                  | Capture the page's bearer token and persist on `window.__spotifyAuth`. Run this first.   |
| `search <query> [--top] [--limit=N]`      | Search; print top results as JSON. Use `--top` for just the #1 hit. Preview before add.  |
| `add <playlistId> <query> [...]`          | Search + add top hit. `--dry-run`, `--require-artist=<sub>` for safety.                  |
| `bulk-add <playlistId> <queries-file>`    | One query per line, jittered 4–7s pacing, verifies every 5 adds, prints a match summary. |
| `list <playlistId>`                       | TSV dump: `<uid>\t<artist>\t<title>\t<uri>`. Paginated at 100.                           |
| `remove <playlistId> --uid=<uid>`         | Exact removal by item uid.                                                               |
| `remove <playlistId> --artist=.. --title=..` | Fuzzy filter; needs `--yes` to commit (or `--dry-run` to preview).                    |
| `dedupe <playlistId> [--dry-run] [--yes]` | Remove all-but-first occurrence of each track URI.                                       |
| `verify <playlistId>`                     | Print `{totalCount, last5items}` as JSON. Call between batches to confirm writes landed. |

All subcommands except `bearer` auto-call `bearer` if `window.__spotifyAuth` is empty.

## Examples

```bash
# 1. One-shot add, with artist verification
spotify-playlist bearer
spotify-playlist add 37i9dQZF1DX1tyCD9QhIWF "Nick Cave Red Right Hand" --require-artist="Nick Cave"

# 2. Bulk-add from a curated file. Each line is "artist — title" (em-dash) or "artist|title".
cat > /tmp/queue.txt <<'EOF'
Ren — Hi Ren
Nick Cave — Red Right Hand
Wardruna — Helvegen
EOF
spotify-playlist bulk-add 2x3jK9mP... /tmp/queue.txt
# → prints per-line "MATCH: Ren — Hi Ren" with the resolved (artist — title),
#   pauses 4–7s between adds, and verifies totalCount every 5 adds.

# 3. Remove by fuzzy filter (dry-run first, then commit)
spotify-playlist remove 2x3jK9mP... --artist="Ren" --title="Hi Ren" --dry-run
spotify-playlist remove 2x3jK9mP... --artist="Ren" --title="Hi Ren" --yes

# 4. Verify (e.g. after a script crash, confirm where you are)
spotify-playlist verify 2x3jK9mP...
# → {"totalCount": 47, "last5items": [{"uid":"...","artist":"...","title":"...","uri":"..."}, ...]}
```

## Don't

- **Don't fire mutations without pacing.** Spotify will return `HTTP 200` with a `{"data":{"addItemsToPlaylist":{"__typename":"AddItemsToPlaylistPayload"}}}` envelope and *silently drop the write* after the first ~5–10 fast calls. The playlist's `totalCount` is the only ground truth. The `bulk-add` subcommand already enforces 4–7s jittered sleeps and re-reads `totalCount` every 5 adds; don't bypass it by writing a `for` loop that calls `add` repeatedly with no sleep.
- **Don't shell-template GraphQL bodies into a JS string.** If you `node -e "fetch(..., { body: '...' + process.argv[1] + '...' })" -- "$query"`, then `process.argv[1]` is the literal `"--"`, not your query — and Spotify's top hit for the string `"--"` is a song called **"Hi Ren" by Ren**. (We have receipts. 36 copies' worth.) Use `browser.fetch(tab, url, { body: jsObject })`, which JSON-serializes a real object. The shipped `.jsh` already does this; do not write a shell-templated alternative.
- **Don't `await` a 30+ second promise inside `browser.evalAsync`.** Chrome DevTools' `Runtime.evaluate` times out at 30s and can detach the CDP session, dropping `window.__spotifyAuth`. Individual search/add operations finish in < 2s so this only bites the "fetch the whole 300-track playlist" path — `list` paginates at limit 100 specifically to avoid it. If you need a long await, kick the promise off (`fetch(...).then(r => window.__last = r); return 'kicked'`) and poll `window.__last` from a separate eval.
- **Don't trust 200s.** After every batch, call `spotify-playlist verify <id>` and confirm `totalCount` advanced by the expected delta and that the recently-added items have the right artists.
- **Don't fall back to `api.spotify.com/v1/*`.** It rate-limits the web-player bearer immediately (429). Stay on `api-partner.spotify.com/pathfinder/v2/query`.

## Re-discovery procedure

If a request returns `"errors":[{"message":"PersistedQueryNotFound"}]` or a 400, Spotify has shipped a new GraphQL schema and the persisted-query SHA hashes are stale. Refresh them by intercepting a live operation in the page; full procedure with copy-paste snippets:

```bash
read_file /workspace/skills/spotify-playlist-driver/references/rediscovery.md
```

## What's saved

Verified working **2026-06-02**. These values are baked into `spotify-playlist.jsh` as constants; if they break, follow the re-discovery procedure above.

| Thing                                  | Value                                                                  |
| -------------------------------------- | ---------------------------------------------------------------------- |
| GraphQL endpoint                       | `https://api-partner.spotify.com/pathfinder/v2/query`                  |
| Auth                                   | `Authorization: Bearer <page-token>` — no `Client-Token` header needed |
| `searchDesktop` sha256Hash             | `d9f785900f0710b31c07818d617f4f7600c1e21217e80f5b043d1e78d74e6026`     |
| `fetchPlaylistContents` sha256Hash     | `a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4`     |
| `addToPlaylist` / `removeFromPlaylist` | `47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990`     |
| Bearer-capture strategy                | Hook **both** `XMLHttpRequest.prototype.setRequestHeader` and `window.fetch`; persist as `window.__spotifyAuth`. |
| Bearer-capture trigger                 | `document.querySelector('main a[href*="/track/"]')?.click()` — clicks a track link, which always provokes a `decorateContextTracks` XHR carrying the bearer. |
| Pacing                                 | 4–7s jittered sleep between mutations. Read `totalCount` every 5 ops.  |

For more worked recipes (rollback after a bad bulk-add, splice a playlist from another, etc.) see:

```bash
read_file /workspace/skills/spotify-playlist-driver/references/recipes.md
```
