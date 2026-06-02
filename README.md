# spotify-playlist-driver

A SLICC skill for bulk-editing Spotify playlists from a logged-in `open.spotify.com` tab — add, remove, dedupe, verify. No OAuth app, no developer registration: it drives the web player's private GraphQL endpoint using the page's own session bearer.

## Why this exists

The Spotify Web Player ships an internal GraphQL at `api-partner.spotify.com/pathfinder/v2/query`. It's perfectly capable of bulk-editing playlists, but using it from an agent requires re-discovering:

- Which endpoint to hit (the public `api.spotify.com/v1/*` rate-limits web-player tokens to 429 instantly).
- How to extract the bearer from the page (hook **both** XHR and fetch — Spotify uses XHR for most pathfinder calls).
- The persisted-query SHA hashes for `searchDesktop`, `fetchPlaylistContents`, `addToPlaylist`, `removeFromPlaylist`.
- That Spotify silently drops writes after ~5–10 fast calls (HTTP 200, empty data envelope) and the cure is 4–7s jittered pacing.
- That `node -e "..." -- "$query"` makes `process.argv[1] === "--"`, whose top Spotify search hit is "Hi Ren" by Ren (we have receipts).
- That long `await`s in `browser.evalAsync` detach the CDP session at 30s.

This skill bakes all of it in.

## Install

In any SLICC session (cone or scoop):

```
upskill jbaruch/spotify-playlist-driver --skill spotify-playlist-driver
```

The skill ships:

- `SKILL.md` — agent-facing instructions.
- `spotify-playlist.jsh` — auto-discovered as a shell command named `spotify-playlist`.
- `references/rediscovery.md` — how to refresh the persisted-query hashes if Spotify ships a new schema.
- `references/recipes.md` — rollback, splice, FIFO-cap, silent-drop diagnostics.

## Quick start

```bash
# 1. Open https://open.spotify.com in a tab and log in.
# 2. Capture the page's bearer.
spotify-playlist bearer

# 3. Preview a search.
spotify-playlist search "Wardruna Helvegen" --top

# 4. Add it to a playlist (verify the artist guard).
spotify-playlist add 54BVZR12w7MsoYFzNIKqGB "Wardruna Helvegen" --require-artist="Wardruna"

# 5. Confirm the write landed.
spotify-playlist verify 54BVZR12w7MsoYFzNIKqGB
```

## Subcommands

| Subcommand                                         | Purpose                                                                                  |
| -------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `bearer`                                           | Capture the page's bearer token (idempotent).                                            |
| `search <query> [--top] [--limit=N]`               | Search; print top N as JSON.                                                             |
| `add <playlistId> <query> [--dry-run] [--require-artist=<sub>]` | Search + add top hit. Accepts a raw `spotify:track:<id>` to skip the search.    |
| `bulk-add <playlistId> <queries-file>`             | Paced 4–7s adds with `totalCount` verification every 5 ops and a final reconciliation.   |
| `list <playlistId>`                                | TSV: `<uid>\t<artist>\t<title>\t<uri>`. Paginated at 100.                                |
| `remove <playlistId> --uid=<uid>`                  | Exact removal by item uid.                                                               |
| `remove <playlistId> --artist=<sub> [--title=<sub>] [--yes\|--dry-run]` | Fuzzy filter; preview-by-default.                                            |
| `dedupe <playlistId> [--yes\|--dry-run]`           | Remove duplicate URIs (keep first occurrence).                                           |
| `verify <playlistId>`                              | Print `{totalCount, last5items}` as JSON.                                                |

Run `spotify-playlist --help` for the canonical reference.

## What if Spotify changes the GraphQL schema?

You'll see a `PersistedQueryNotFound` error. Follow `references/rediscovery.md` — there's a copy-paste console snippet that hooks XHR + fetch, captures the live operation hashes from a UI action, and tells you exactly which constants in `spotify-playlist.jsh` to update.

## License

MIT. PRs welcome.

## Provenance

Authored against `https://open.spotify.com` as of **2026-06-02**. Hashes verified that day on an actual playlist (a 290-track "Metal & Disco Fusion Extended" mix that I — the human user — got tired of curating manually). Sliccy did the discovery, a scoop wrote the skill.
