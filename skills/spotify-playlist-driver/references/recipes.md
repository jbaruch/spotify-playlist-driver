# spotify-playlist recipes

Worked examples for less-obvious workflows. Assume `PL=2x3jK9mP...` is your playlist id and that `spotify-playlist bearer` has already succeeded in the current page session.

## Roll back a bad bulk-add

Bulk-add resolved a fuzzy query to the wrong track. Identify and remove:

```bash
# 1. Capture the current state to a file.
spotify-playlist list "$PL" > /tmp/playlist.tsv

# 2. Eyeball or grep for the wrong matches.
grep -i 'wrong artist' /tmp/playlist.tsv
# → <uid>\twrong artist\twrong title\tspotify:track:...

# 3. Remove by uid (exact, fast).
spotify-playlist remove "$PL" --uid=<uid>

# Repeat for each bad row. Or pipe:
grep -i 'wrong artist' /tmp/playlist.tsv | cut -f1 | while read uid; do
  spotify-playlist remove "$PL" --uid="$uid"
  sleep 5   # pacing
done
spotify-playlist verify "$PL"
```

## Splice tracks from playlist A into playlist B

```bash
spotify-playlist list "$SRC" > /tmp/src.tsv
# Extract URIs (column 4), feed them in as exact matches via `add`.
cut -f4 /tmp/src.tsv | sed 's|spotify:track:||' \
  | sed 's|^|spotify:track:|' > /tmp/src-uris.txt
# For exact URIs you can use the `search` resolver for the title or call add
# directly with the URI in the query — `add` accepts a `spotify:track:<id>` and
# skips the search step.
while read q; do
  spotify-playlist add "$DST" "$q"
  sleep $((4 + RANDOM % 4))
done < /tmp/src-uris.txt
spotify-playlist verify "$DST"
```

## Dedupe with preview

```bash
spotify-playlist dedupe "$PL" --dry-run    # prints the (uid, uri) pairs that would be removed
spotify-playlist dedupe "$PL" --yes        # commit
spotify-playlist verify "$PL"              # confirm totalCount dropped by the expected amount
```

## Cap a playlist at N tracks (FIFO)

Drop the oldest items so the playlist stays at most 100 tracks:

```bash
N=100
total=$(spotify-playlist verify "$PL" | jq .totalCount)
excess=$((total - N))
if [ "$excess" -gt 0 ]; then
  spotify-playlist list "$PL" | head -n "$excess" | cut -f1 | while read uid; do
    spotify-playlist remove "$PL" --uid="$uid"
    sleep 5
  done
fi
spotify-playlist verify "$PL"
```

## "Did Spotify drop my writes?" diagnostic

After a bulk operation, compare expected vs actual:

```bash
expected=42
actual=$(spotify-playlist verify "$PL" | jq .totalCount)
if [ "$actual" -lt "$expected" ]; then
  echo "Spotify dropped $((expected - actual)) writes. Pause 30s and retry the missing ones."
  # Cross-reference the bulk-add summary log against `list` to find the gaps.
fi
```

A drop is almost always a pacing problem. The fix is a longer sleep — 8–10s instead of 4–7s — not a retry storm.
