# Re-discovering Spotify's persisted-query SHA hashes

The web player's GraphQL operations are sent as **persisted queries** — the body carries an `extensions.persistedQuery.sha256Hash` instead of a query string, and the server resolves the hash against its registered operations. When Spotify ships a new schema, the hash for an operation changes. Symptoms:

- `HTTP 400` with `{"errors":[{"message":"PersistedQueryNotFound", "extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}` — the most common.
- `HTTP 200` with `{"errors":[{"message":"Cannot query field ..."}]}` — schema change, may need new `variables` too.

Recover by intercepting the live operation in the page and reading the hash off the wire.

## 1. Open the tab

`https://open.spotify.com` in a logged-in tab. Open DevTools → Console.

## 2. Install the interceptor

Spotify uses **XHR for most pathfinder calls**, with `fetch` mixed in. Hook both. Paste into the console:

```javascript
(() => {
  if (window.__spotInstalled) return 'already installed';
  window.__spotInstalled = true;
  window.__spotOps = window.__spotOps || {};

  // XHR hook — captures the URL on open, body on send.
  const _open = XMLHttpRequest.prototype.open;
  const _send = XMLHttpRequest.prototype.send;
  const _setH = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function (m, u, ...rest) {
    this.__url = u;
    return _open.call(this, m, u, ...rest);
  };
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
    if (/^authorization$/i.test(k) && /^Bearer /.test(v)) window.__spotifyAuth = v.slice(7);
    return _setH.call(this, k, v);
  };
  XMLHttpRequest.prototype.send = function (body) {
    if (this.__url && this.__url.includes('pathfinder') && body) {
      try {
        const p = typeof body === 'string' ? JSON.parse(body) : body;
        if (p?.operationName && p?.extensions?.persistedQuery?.sha256Hash) {
          window.__spotOps[p.operationName] = {
            sha256Hash: p.extensions.persistedQuery.sha256Hash,
            variables: p.variables,
          };
        }
      } catch {}
    }
    return _send.call(this, body);
  };

  // fetch hook — same idea for completeness.
  const _fetch = window.fetch;
  window.fetch = async function (input, init) {
    try {
      const url = typeof input === 'string' ? input : input?.url;
      const headers = new Headers(init?.headers || (typeof input === 'object' ? input?.headers : {}));
      const auth = headers.get('authorization');
      if (auth && /^Bearer /.test(auth)) window.__spotifyAuth = auth.slice(7);
      if (url && url.includes('pathfinder') && init?.body) {
        const p = typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
        if (p?.operationName && p?.extensions?.persistedQuery?.sha256Hash) {
          window.__spotOps[p.operationName] = {
            sha256Hash: p.extensions.persistedQuery.sha256Hash,
            variables: p.variables,
          };
        }
      }
    } catch {}
    return _fetch.call(this, input, init);
  };

  return 'installed';
})();
```

## 3. Trigger each operation

Doing UI actions causes the page to fire each operation. The reliable triggers:

| Operation                | UI action                                                                               |
| ------------------------ | --------------------------------------------------------------------------------------- |
| `searchDesktop`          | Type any string in the search box.                                                      |
| `fetchPlaylistContents`  | Click into any playlist.                                                                |
| `addToPlaylist`          | Right-click a track → Add to Playlist → pick any list. Then **undo it** with the next op. |
| `removeFromPlaylist`     | In a playlist you own, right-click a track → Remove from this playlist.                  |

You can also trigger a bearer capture (without changing any operation) by clicking any track link:

```javascript
document.querySelector('main a[href*="/track/"]')?.click();
```

## 4. Read out the captured hashes

```javascript
console.log(JSON.stringify(window.__spotOps, null, 2));
console.log('bearer:', (window.__spotifyAuth || '').slice(0, 20) + '...');
```

Copy the `sha256Hash` for each of:

- `searchDesktop`
- `fetchPlaylistContents`
- `addToPlaylist`
- `removeFromPlaylist`

…into the constants block at the top of `/workspace/skills/spotify-playlist-driver/spotify-playlist.jsh` and bump the "verified" date in `SKILL.md`'s "What's saved" section.

## 5. Sanity-check `variables` shape

The `variables` shape can drift too. The `.jsh` builds these by hand:

- `searchDesktop`: `{searchTerm, offset, limit, numberOfTopResults, includeAudiobooks:false, includeArtistHasConcertsField:false, includePreReleases:false, includeLocalConcertsField:false}`
- `fetchPlaylistContents`: `{uri: 'spotify:playlist:<id>', offset, limit, includeEpisodeContentRatingsV2:true}`
- `addToPlaylist`: `{playlistItemUris: ['spotify:track:<id>',...], playlistUri: 'spotify:playlist:<id>', newPosition: {moveType: 'BOTTOM_OF_PLAYLIST', fromUid: null}}`
- `removeFromPlaylist`: `{playlistUri: 'spotify:playlist:<id>', uids: ['<uid>',...]}`

Compare `window.__spotOps[<op>].variables` from the live capture against the above. If new required fields appear, add them to the `.jsh` builders.

## 6. Test before declaring fixed

```bash
spotify-playlist bearer
spotify-playlist search "Wardruna Helvegen" --top
spotify-playlist verify <playlistId>   # confirm reads work
spotify-playlist add <playlistId> "Wardruna Helvegen" --dry-run  # confirm resolve works
```

Then a real add + a `verify` to confirm `totalCount` advanced by 1.
