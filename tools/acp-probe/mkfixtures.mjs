// Build redacted JSONL fixtures for F-01 from the raw probe logs.
import { readFileSync, writeFileSync } from 'node:fs';

const SPIKE = process.argv[2];
const OUT = process.argv[3];

const read = (f) =>
  readFileSync(`${SPIKE}/${f}`, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));

// ---- redaction -------------------------------------------------------------
const repoMap = new Map();
let repoN = 0;
function fakeRepo(name) {
  if (!repoMap.has(name)) repoMap.set(name, `example-org/repo-${++repoN}`);
  return repoMap.get(name);
}

function redact(value) {
  if (typeof value === 'string') {
    let s = value;
    // GitHub/GitLab owner/name pairs seen in repository records and URLs.
    for (const [real, fake] of repoMap) s = s.replaceAll(real, fake);
    s = s.replace(/[\w.+-]+@[\w.-]+\.\w+/g, 'user@example.com');
    s = s.replace(/\/home\/[^/"\s]+/g, '/home/user');
    s = s.replace(/\/tmp\/claude-[^"\s]*?\/ws/g, '/home/user/workspace');
    s = s.replace(/\/tmp\/claude-[^"\s]*/g, '/home/user/workspace');
    // Bearer-ish blobs and OAuth codes.
    s = s.replace(/([?&](?:code|token|access_token|user_code)=)[^&"\s]+/gi, '$1REDACTED');
    s = s.replace(/\b[A-Z0-9]{4}-[A-Z0-9]{4}\b/g, 'XXXX-XXXX');
    // The user's configured MCP servers are theirs, not part of the protocol shape.
    s = s.replace(/\blinear\b/gi, 'example-mcp');
    s = s.replace(/https:\/\/mcp\.[\w.-]+\/\S*/gi, 'https://mcp.example.com/authorize?...');
    return s;
  }
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = redact(v);
    return out;
  }
  return value;
}

// Pre-seed the repo map so names are replaced consistently everywhere.
function collectRepos(frames) {
  const walk = (v) => {
    if (Array.isArray(v)) return v.forEach(walk);
    if (v && typeof v === 'object') {
      if (v.providerType && typeof v.name === 'string' && v.name.includes('/')) fakeRepo(v.name);
      Object.values(v).forEach(walk);
    }
  };
  frames.forEach(walk);
}

// ---- fixture writers -------------------------------------------------------
// Structural scrub: session titles, cwds and free-text message content carry the
// user's private work. The fixtures exist for frame *shape*, so replace them.
function scrub(v, opts = {}) {
  if (Array.isArray(v)) return v.map((x) => scrub(x, opts));
  if (!v || typeof v !== 'object') return v;
  const out = {};
  for (const [k, val] of Object.entries(v)) {
    if (k === 'title' && typeof val === 'string') out[k] = 'Example session';
    else if (k === 'cwd' && typeof val === 'string' && val) out[k] = '/home/user/workspace';
    else if (k === 'workspacePaths' && Array.isArray(val)) out[k] = val.map(() => '/home/user/workspace');
    else if (opts.text && k === 'text' && typeof val === 'string') out[k] = 'redacted message text';
    else out[k] = scrub(val, opts);
  }
  return out;
}

function wire(frames) {
  // Keep only what a client would actually see on the wire.
  return frames.filter((o) => o.dir === 'out' || o.dir === 'in').map((o) => ({
    dir: o.dir === 'out' ? 'client->agent' : 'agent->client',
    frame: redact(o.frame),
  }));
}

function write(name, rows, header, opts = {}) {
  rows = rows.map((r) => ({ ...r, frame: scrub(r.frame, opts) }));
  const lines = [JSON.stringify({ _fixture: name, _note: header })];
  for (const r of rows) lines.push(JSON.stringify(r));
  writeFileSync(`${OUT}/${name}`, lines.join('\n') + '\n');
  console.log(`${name}: ${rows.length} frames`);
}

// 1. handshake
write(
  'initialize-v3.jsonl',
  wire(read('init-v3.jsonl')),
  'kiro-cli 2.19.2 acp --agent-engine v3 --auth-method cli. The initialize handshake, including the _meta.kiro capability block that names the extension prefix, session sources, list scopes and execution targets.',
);

// 2. remote session listing
const list3 = read('list3.jsonl');
collectRepos(list3.map((o) => o.frame));
write(
  'session-list-remote.jsonl',
  wire(list3),
  'session/list dispatched to the remote store via params._meta.kiro.{sessionSource,listScope}. Proves cloud sessions are discoverable over ACP. Repository names and the account email are redacted.',
);

// 3. source providers
const list2 = read('list2.jsonl');
collectRepos(list2.map((o) => o.frame));
write(
  'source-providers.jsonl',
  wire(list2).slice(0, 4),
  '_kiro/sourceProviders/list and /listResources — the programmatic repository picker behind a cloud session create. Repository names redacted.',
);

// 4. cloud session load + replay (head only; the full replay is ~1000 frames)
const load = read('load.jsonl');
collectRepos(load.map((o) => o.frame));
const loadWire = wire(load);
write(
  'session-load-remote-head.jsonl',
  loadWire.slice(0, 60),
  'session/load against a cloud-sandbox session, followed by the head of its history replay (991 session/update notifications in the full run). Truncated to 60 frames; message text is replaced with a placeholder. Regenerate with the probe for the full stream.',
  { text: true },
);

// 5. full prompt turn incl. permission
const perm = read('perm-local.jsonl');
collectRepos(perm.map((o) => o.frame));
write(
  'prompt-turn-with-permission.jsonl',
  wire(perm),
  'A complete session/prompt turn on a local session: turn_start, tool_call, session/request_permission (server-initiated) answered with reject, tool_call_update, streamed agent_message_chunks, turn_end with stopReason, and the per-turn credit summary. Paths redacted.',
);
