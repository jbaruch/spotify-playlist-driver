// spotify-playlist — drive Spotify Web Player's private GraphQL to bulk-edit
// playlists from a logged-in open.spotify.com tab.
//
// All knowledge baked in here (endpoint, persisted-query hashes, bearer-capture
// strategy, pacing) is documented in SKILL.md. If a hash 400s with
// PersistedQueryNotFound, see references/rediscovery.md.
//
// Subcommands:
//   bearer
//   search   <query> [--top] [--limit=N]
//   add      <playlistId> <query>                [--dry-run] [--require-artist=<sub>]
//   bulk-add <playlistId> <queries-file>         [--min-delay=4] [--max-delay=7]
//   list     <playlistId>
//   remove   <playlistId> --uid=<uid>
//   remove   <playlistId> --artist=<sub> --title=<sub>   [--yes | --dry-run]
//   dedupe   <playlistId>                        [--yes | --dry-run]
//   verify   <playlistId>

// ─── Constants (verified 2026-06-02) ──────────────────────────────────────────

const ENDPOINT = 'https://api-partner.spotify.com/pathfinder/v2/query';

const HASH = {
  searchDesktop:          'd9f785900f0710b31c07818d617f4f7600c1e21217e80f5b043d1e78d74e6026',
  fetchPlaylistContents:  'a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4',
  addToPlaylist:          '47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990',
  removeFromPlaylist:     '47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990',
};

// ─── Entry point ──────────────────────────────────────────────────────────────

const { positional, flags } = process.argv.parseFlags();
const [cmd] = positional;

if (!cmd || flags.help || flags.h) {
  cli.out(
`spotify-playlist <subcommand> [args]

  bearer                                          capture page bearer onto window.__spotifyAuth
  search <query> [--top] [--limit=N]              preview top N (default 5) matches as JSON
  add <playlistId> <query> [--dry-run]            search + add top hit
                  [--require-artist=<sub>]        fail if resolved artist doesn't match
  bulk-add <playlistId> <queries-file>            one query per line, 4-7s jittered pacing
                  [--min-delay=4] [--max-delay=7]
  list <playlistId>                               TSV: <uid>\\t<artist>\\t<title>\\t<uri>
  remove <playlistId> --uid=<uid>                 remove by exact item uid
  remove <playlistId> --artist=<sub> --title=<sub> [--yes|--dry-run]
  dedupe <playlistId> [--yes|--dry-run]           remove duplicate URIs (keep first)
  verify <playlistId>                             {totalCount, last5items} as JSON
`);
  process.exit(0);
}

const tab = await browser.findTab({ domain: 'open.spotify.com' });
if (!tab) cli.die('no open.spotify.com tab found. open the Spotify Web Player and log in first.', { prefix: 'spotify-playlist' });

switch (cmd) {
  case 'bearer':   await cmdBearer();                                  break;
  case 'search':   await cmdSearch(positional[1], flags);              break;
  case 'add':      await cmdAdd(positional[1], positional[2], flags);  break;
  case 'bulk-add': await cmdBulkAdd(positional[1], positional[2], flags); break;
  case 'list':     await cmdList(positional[1]);                       break;
  case 'remove':   await cmdRemove(positional[1], flags);              break;
  case 'dedupe':   await cmdDedupe(positional[1], flags);              break;
  case 'verify':   await cmdVerify(positional[1]);                     break;
  default:         cli.die(`unknown subcommand: ${cmd}`, { prefix: 'spotify-playlist' });
}

// ─── Subcommands ──────────────────────────────────────────────────────────────

async function cmdBearer() {
  const { bearer, fresh } = await ensureBearer({ force: true });
  const tag = fresh ? c.green('captured') : c.dim('reused');
  console.log(`${tag} bearer: ${bearer.slice(0, 24)}... (length ${bearer.length})`);
}

async function cmdSearch(query, flags) {
  if (!query) cli.die('search: missing query', { prefix: 'spotify-playlist' });
  await ensureBearer();
  const limit = Number(flags.limit ?? 5);
  const hits = await spotifySearch(query, limit);
  if (flags.top) {
    cli.out(hits[0] ?? null);
  } else {
    cli.out(hits);
  }
}

async function cmdAdd(playlistId, query, flags) {
  if (!playlistId) cli.die('add: missing <playlistId>', { prefix: 'spotify-playlist' });
  if (!query)      cli.die('add: missing <query>',      { prefix: 'spotify-playlist' });
  await ensureBearer();

  // Accept a raw URI as the query — short-circuit the search step.
  let resolved;
  if (/^spotify:track:[A-Za-z0-9]+$/.test(query)) {
    resolved = { uri: query, name: '(uri)', artist: '(uri)' };
  } else {
    const hits = await spotifySearch(query, 1);
    if (!hits.length) cli.die(`no match for: ${query}`, { prefix: 'spotify-playlist' });
    resolved = hits[0];
  }

  console.log(`${c.cyan('MATCH:')} ${resolved.artist} ${c.dim('—')} ${resolved.name}  ${c.dim(resolved.uri)}`);

  if (flags['require-artist']) {
    const want = String(flags['require-artist']).toLowerCase();
    if (!resolved.artist.toLowerCase().includes(want)) {
      cli.die(`require-artist "${flags['require-artist']}" does not match resolved "${resolved.artist}"`, { prefix: 'spotify-playlist' });
    }
  }

  if (flags['dry-run']) {
    console.log(c.dim('(dry-run, not adding)'));
    return;
  }

  await gqlAdd(playlistId, [resolved.uri]);
  console.log(c.green('+ added'));
}

async function cmdBulkAdd(playlistId, file, flags) {
  if (!playlistId) cli.die('bulk-add: missing <playlistId>',     { prefix: 'spotify-playlist' });
  if (!file)       cli.die('bulk-add: missing <queries-file>',   { prefix: 'spotify-playlist' });
  if (!(await fs.exists(file))) cli.die(`bulk-add: file not found: ${file}`, { prefix: 'spotify-playlist' });
  await ensureBearer();

  const lines = (await fs.readFile(file))
    .split('\n')
    .map((l) => l.replace(/^\uFEFF/, '').trim())
    .filter((l) => l && !l.startsWith('#'));

  if (!lines.length) cli.die('bulk-add: file has no non-empty lines', { prefix: 'spotify-playlist' });

  // Normalize "artist | title" or "artist — title" or "artist - title" into a single search string.
  const queries = lines.map((line) => {
    const m = line.split(/\s*[|—–]\s*| - /);
    return m.length >= 2 ? `${m[0]} ${m.slice(1).join(' ')}`.trim() : line;
  });

  const minDelay = Number(flags['min-delay'] ?? 4);
  const maxDelay = Number(flags['max-delay'] ?? 7);

  const startCount = await fetchTotalCount(playlistId);
  console.log(c.dim(`starting playlist size: ${startCount}`));

  const summary = []; // {query, ok, resolved?, error?}
  let lastVerifiedAt = 0;

  for (let i = 0; i < queries.length; i++) {
    const q = queries[i];
    const tag = c.dim(`[${i + 1}/${queries.length}]`);
    try {
      const hits = await spotifySearch(q, 1);
      if (!hits.length) throw new Error('no search match');
      const t = hits[0];
      await gqlAdd(playlistId, [t.uri]);
      summary.push({ query: q, ok: true, resolved: t });
      console.log(`${tag} ${c.green('+')} ${t.artist} ${c.dim('—')} ${t.name}   ${c.dim(`(query: ${q})`)}`);
    } catch (err) {
      summary.push({ query: q, ok: false, error: String(err.message || err) });
      console.log(`${tag} ${c.red('x')} ${q}   ${c.dim(err.message || String(err))}`);
    }

    // Verify every 5 successful adds.
    const adds = summary.filter((s) => s.ok).length;
    if (adds > 0 && adds - lastVerifiedAt >= 5) {
      const t = await fetchTotalCount(playlistId);
      const expected = startCount + adds;
      const ok = t >= expected;
      console.log(c.dim(`  · verify after ${adds}: totalCount=${t} expected≥${expected} ${ok ? c.green('ok') : c.red('LAG')}`));
      lastVerifiedAt = adds;
      if (!ok) console.log(c.yellow('  · spotify may be silently dropping writes — consider raising --min-delay'));
    }

    // Jittered pacing between mutations. No sleep after the very last item.
    if (i < queries.length - 1) await sleepJittered(minDelay, maxDelay);
  }

  // Final summary.
  const okCount = summary.filter((s) => s.ok).length;
  const finalCount = await fetchTotalCount(playlistId);
  console.log('');
  console.log(c.bold('— summary —'));
  console.log(`requested: ${queries.length}, resolved+sent: ${okCount}, failed: ${queries.length - okCount}`);
  console.log(`playlist totalCount: ${startCount} → ${finalCount} (delta ${finalCount - startCount}, expected ${okCount})`);
  if (finalCount - startCount < okCount) {
    console.log(c.red(`silent-drop detected: ${okCount - (finalCount - startCount)} writes did not land. Pause 30s, then re-run the missing queries.`));
  }
  console.log('');
  console.log(c.bold('— matches —'));
  for (const s of summary) {
    if (s.ok) console.log(`  ok   ${s.resolved.artist} — ${s.resolved.name}   ${c.dim('(query: ' + s.query + ')')}`);
    else      console.log(`  ${c.red('FAIL')} ${s.query}   ${c.dim(s.error)}`);
  }
}

async function cmdList(playlistId) {
  if (!playlistId) cli.die('list: missing <playlistId>', { prefix: 'spotify-playlist' });
  await ensureBearer();
  const items = await fetchAllItems(playlistId);
  for (const it of items) {
    process.stdout.write(`${it.uid}\t${it.artist}\t${it.title}\t${it.uri}\n`);
  }
}

async function cmdRemove(playlistId, flags) {
  if (!playlistId) cli.die('remove: missing <playlistId>', { prefix: 'spotify-playlist' });
  await ensureBearer();

  if (flags.uid) {
    await gqlRemove(playlistId, [String(flags.uid)]);
    console.log(c.green(`- removed uid ${flags.uid}`));
    return;
  }

  if (!flags.artist && !flags.title) {
    cli.die('remove: pass --uid=<uid> OR --artist=<sub> [--title=<sub>]', { prefix: 'spotify-playlist' });
  }

  const items = await fetchAllItems(playlistId);
  const artistSub = flags.artist ? String(flags.artist).toLowerCase() : null;
  const titleSub  = flags.title  ? String(flags.title).toLowerCase()  : null;
  const matches = items.filter((it) =>
    (!artistSub || it.artist.toLowerCase().includes(artistSub)) &&
    (!titleSub  || it.title .toLowerCase().includes(titleSub))
  );

  if (!matches.length) cli.die('remove: no items matched the filter', { prefix: 'spotify-playlist' });

  console.log(c.bold(`will remove ${matches.length} item(s):`));
  for (const m of matches) console.log(`  ${m.uid}\t${m.artist} — ${m.title}`);

  if (flags['dry-run']) {
    console.log(c.dim('(dry-run, nothing removed)'));
    return;
  }
  if (!flags.yes) cli.die('refusing to remove without --yes (or pass --dry-run to preview)', { prefix: 'spotify-playlist' });

  // Spotify accepts multiple uids in one removeItemsFromPlaylist call. Keep
  // batches small and paced anyway, since the same silent-drop rule applies.
  const BATCH = 50;
  for (let i = 0; i < matches.length; i += BATCH) {
    const slice = matches.slice(i, i + BATCH);
    await gqlRemove(playlistId, slice.map((m) => m.uid));
    console.log(c.green(`- removed ${slice.length} (${i + slice.length}/${matches.length})`));
    if (i + BATCH < matches.length) await sleepJittered(4, 7);
  }
}

async function cmdDedupe(playlistId, flags) {
  if (!playlistId) cli.die('dedupe: missing <playlistId>', { prefix: 'spotify-playlist' });
  await ensureBearer();
  const items = await fetchAllItems(playlistId);
  const seen = new Set();
  const dupes = [];
  for (const it of items) {
    if (seen.has(it.uri)) dupes.push(it);
    else seen.add(it.uri);
  }
  if (!dupes.length) {
    console.log(c.green('no duplicates'));
    return;
  }
  console.log(c.bold(`will remove ${dupes.length} duplicate(s):`));
  for (const d of dupes) console.log(`  ${d.uid}\t${d.artist} — ${d.title}`);
  if (flags['dry-run']) { console.log(c.dim('(dry-run)')); return; }
  if (!flags.yes)       cli.die('refusing to dedupe without --yes (or pass --dry-run)', { prefix: 'spotify-playlist' });

  const BATCH = 50;
  for (let i = 0; i < dupes.length; i += BATCH) {
    const slice = dupes.slice(i, i + BATCH);
    await gqlRemove(playlistId, slice.map((d) => d.uid));
    console.log(c.green(`- removed ${slice.length} (${i + slice.length}/${dupes.length})`));
    if (i + BATCH < dupes.length) await sleepJittered(4, 7);
  }
}

async function cmdVerify(playlistId) {
  if (!playlistId) cli.die('verify: missing <playlistId>', { prefix: 'spotify-playlist' });
  await ensureBearer();
  const page = await gqlFetchPlaylistPage(playlistId, 0, 1);
  const total = page?.data?.playlistV2?.content?.totalCount ?? 0;
  // Last 5: read the tail page.
  const tailOffset = Math.max(0, total - 5);
  const tailPage = await gqlFetchPlaylistPage(playlistId, tailOffset, 5);
  const last5 = (tailPage?.data?.playlistV2?.content?.items ?? []).map(itemToRow);
  cli.out({ totalCount: total, last5items: last5 });
}

// ─── Auth / bearer capture ────────────────────────────────────────────────────

async function ensureBearer({ force = false } = {}) {
  // 1. Cheap path: bearer already stashed on window from a previous call.
  if (!force) {
    const cached = await browser.eval(tab, () => window.__spotifyAuth || null);
    if (cached && typeof cached === 'string' && cached.length > 40) {
      return { bearer: cached, fresh: false };
    }
  }

  // 2. Install hooks (idempotent) and trigger a track-link click to provoke an XHR.
  await browser.eval(tab, () => {
    if (window.__spotInstalled) return 'already';
    window.__spotInstalled = true;
    const _setH = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
      if (/^authorization$/i.test(k) && /^Bearer /.test(v)) window.__spotifyAuth = v.slice(7);
      return _setH.call(this, k, v);
    };
    const _fetch = window.fetch;
    window.fetch = async function (input, init) {
      try {
        const headers = new Headers(init?.headers || (typeof input === 'object' ? input?.headers : {}));
        const auth = headers.get('authorization');
        if (auth && /^Bearer /.test(auth)) window.__spotifyAuth = auth.slice(7);
      } catch {}
      return _fetch.call(this, input, init);
    };
    return 'installed';
  });

  // Trigger: click any track link. That always provokes a decorateContextTracks
  // XHR with a fresh Authorization header.
  await browser.eval(tab, () => {
    const a = document.querySelector('main a[href*="/track/"]');
    if (a) a.click();
    return a ? 'clicked' : 'no-track-link';
  });

  // Poll for the bearer to land (up to ~5s).
  for (let i = 0; i < 25; i++) {
    await sleep(200);
    const got = await browser.eval(tab, () => window.__spotifyAuth || null);
    if (got && typeof got === 'string' && got.length > 40) return { bearer: got, fresh: true };
  }

  cli.die(
    'could not capture bearer. Open the Spotify Web Player tab, click any track in the main area, then retry `spotify-playlist bearer`.',
    { prefix: 'spotify-playlist' }
  );
}

// ─── GraphQL helpers (all routed through the page so cookies are automatic) ───

async function gql(operationName, sha256Hash, variables) {
  const bearer = await browser.eval(tab, () => window.__spotifyAuth || null);
  if (!bearer) cli.die('bearer missing — run `spotify-playlist bearer` first', { prefix: 'spotify-playlist' });

  const body = {
    operationName,
    variables,
    extensions: { persistedQuery: { version: 1, sha256Hash } },
  };

  const resp = await browser.fetch(tab, ENDPOINT, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Authorization': `Bearer ${bearer}`,
    },
    body,
  });

  if (resp.status === 401) {
    cli.die('bearer expired (401). Re-run `spotify-playlist bearer`.', { prefix: 'spotify-playlist' });
  }
  if (resp.status === 429) {
    cli.die('rate limited (429). Pause 30s and reduce concurrency / raise --min-delay.', { prefix: 'spotify-playlist' });
  }
  if (!resp.ok) {
    throw new Error(`graphql ${operationName} HTTP ${resp.status}: ${truncate(stringify(resp.body), 300)}`);
  }

  // Body is already parsed when JSON.
  const data = typeof resp.body === 'string' ? safeJSON(resp.body) : resp.body;
  if (data?.errors?.length) {
    const msg = data.errors.map((e) => e.message).join('; ');
    if (/PersistedQueryNotFound|persisted query/i.test(msg)) {
      cli.die(
        `graphql ${operationName}: PersistedQueryNotFound — Spotify shipped a new schema. See references/rediscovery.md to refresh the hash.`,
        { prefix: 'spotify-playlist' }
      );
    }
    throw new Error(`graphql ${operationName} errors: ${msg}`);
  }
  // Silent-drop heuristic for mutations: if the data envelope is empty/null,
  // surface it loudly rather than letting the caller mistake it for success.
  if (operationName === 'addToPlaylist' && !data?.data?.addItemsToPlaylist) {
    throw new Error('add: response had no addItemsToPlaylist payload — Spotify may be rate-limiting writes (pause 30s)');
  }
  if (operationName === 'removeFromPlaylist' && !data?.data?.removeItemsFromPlaylist) {
    throw new Error('remove: response had no removeItemsFromPlaylist payload — Spotify may be rate-limiting writes (pause 30s)');
  }
  return data;
}

async function spotifySearch(searchTerm, limit) {
  const data = await gql('searchDesktop', HASH.searchDesktop, {
    searchTerm,
    offset: 0,
    limit,
    numberOfTopResults: limit,
    includeAudiobooks: false,
    includeArtistHasConcertsField: false,
    includePreReleases: false,
    includeLocalConcertsField: false,
  });
  const items = data?.data?.searchV2?.tracksV2?.items ?? [];
  return items
    .map((it) => it?.item?.data)
    .filter(Boolean)
    .map((d) => ({
      uri: d.uri,
      name: d.name,
      artist: (d.artists?.items ?? []).map((a) => a.profile?.name).filter(Boolean).join(', '),
    }));
}

async function gqlAdd(playlistId, trackUris) {
  return gql('addToPlaylist', HASH.addToPlaylist, {
    playlistItemUris: trackUris,
    playlistUri: `spotify:playlist:${playlistId}`,
    newPosition: { moveType: 'BOTTOM_OF_PLAYLIST', fromUid: null },
  });
}

async function gqlRemove(playlistId, uids) {
  return gql('removeFromPlaylist', HASH.removeFromPlaylist, {
    playlistUri: `spotify:playlist:${playlistId}`,
    uids,
  });
}

async function gqlFetchPlaylistPage(playlistId, offset, limit) {
  return gql('fetchPlaylistContents', HASH.fetchPlaylistContents, {
    uri: `spotify:playlist:${playlistId}`,
    offset,
    limit,
    includeEpisodeContentRatingsV2: true,
  });
}

async function fetchTotalCount(playlistId) {
  const page = await gqlFetchPlaylistPage(playlistId, 0, 1);
  return page?.data?.playlistV2?.content?.totalCount ?? 0;
}

async function fetchAllItems(playlistId) {
  // Page at 100 to stay well clear of the 30s CDP eval timeout.
  const LIMIT = 100;
  const out = [];
  let offset = 0;
  for (;;) {
    const page = await gqlFetchPlaylistPage(playlistId, offset, LIMIT);
    const content = page?.data?.playlistV2?.content;
    const items = content?.items ?? [];
    for (const it of items) out.push(itemToRow(it));
    offset += items.length;
    if (!items.length || offset >= (content?.totalCount ?? 0)) break;
  }
  return out;
}

function itemToRow(item) {
  const d = item?.itemV2?.data ?? {};
  const artists = (d.artists?.items ?? []).map((a) => a.profile?.name).filter(Boolean).join(', ');
  return {
    uid: item?.uid ?? '',
    artist: artists,
    title: d.name ?? '',
    uri: d.uri ?? '',
  };
}

// ─── Utilities ────────────────────────────────────────────────────────────────

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function sleepJittered(minSec, maxSec) {
  const lo = Math.max(0, Number(minSec) || 0);
  const hi = Math.max(lo, Number(maxSec) || lo);
  const ms = Math.round((lo + Math.random() * (hi - lo)) * 1000);
  await sleep(ms);
}

function stringify(v) { try { return JSON.stringify(v); } catch { return String(v); } }
function safeJSON(s)  { try { return JSON.parse(s);    } catch { return null; } }
function truncate(s, n) { s = String(s ?? ''); return s.length <= n ? s : s.slice(0, n) + '…'; }
