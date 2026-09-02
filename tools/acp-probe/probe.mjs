#!/usr/bin/env node
// F-01 ACP probe: spawn `kiro-cli acp`, run a scripted set of JSON-RPC calls,
// log every frame both ways to JSONL. Usage:
//   node probe.mjs <out.jsonl> <script.json> [-- extra kiro-cli acp args...]
import { spawn } from 'node:child_process';
import { appendFileSync, writeFileSync, readFileSync } from 'node:fs';

const [, , outPath, scriptPath, ...rest] = process.argv;
const extraArgs = rest[0] === '--' ? rest.slice(1) : rest;
const steps = JSON.parse(readFileSync(scriptPath, 'utf8'));

writeFileSync(outPath, '');
const log = (dir, obj) =>
  appendFileSync(outPath, JSON.stringify({ t: Date.now(), dir, frame: obj }) + '\n');

const child = spawn('kiro-cli', ['acp', ...extraArgs], {
  cwd: process.env.PROBE_CWD || process.env.HOME,
  stdio: ['pipe', 'pipe', 'pipe'],
});

let buf = '';
const pending = new Map();

child.stdout.on('data', (d) => {
  buf += d.toString();
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { log('stdout-raw', line); continue; }
    log('in', msg);
    if (msg.id !== undefined && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
    // Auto-answer server-initiated requests so the run doesn't wedge.
    if (msg.method && msg.id !== undefined) {
      const auto = autoAnswer(msg);
      if (auto) send({ jsonrpc: '2.0', id: msg.id, result: auto });
    }
  }
});

child.stderr.on('data', (d) => log('stderr', d.toString()));
child.on('exit', (code, sig) => { log('exit', { code, sig }); process.exit(0); });

function autoAnswer(req) {
  const m = req.method;
  if (m.endsWith('request_permission') || m.includes('permission')) {
    const opts = req.params?.options || [];
    const rejected = opts.find((o) => /reject|deny|no/i.test(o.optionId || o.kind || ''));
    return { outcome: { outcome: 'selected', optionId: (rejected || opts[0])?.optionId } };
  }
  if (m.includes('fs/') || m.includes('terminal')) return null; // we advertised false
  return null;
}

let nextId = 1;
let lastSessionId = null;
function send(obj) { log('out', obj); child.stdin.write(JSON.stringify(obj) + '\n'); }
function call(method, params) {
  const id = nextId++;
  return new Promise((resolve) => {
    pending.set(id, resolve);
    send({ jsonrpc: '2.0', id, method, params });
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  for (const step of steps) {
    if (step.sleep) { await sleep(step.sleep); continue; }
    const params = JSON.parse(
      JSON.stringify(step.params ?? {}).replaceAll('__SESSION__', lastSessionId ?? ''),
    );
    const res = await Promise.race([
      call(step.method, params),
      sleep(step.timeout || 30000).then(() => ({ __timeout: true })),
    ]);
    log('step-result', { method: step.method, res });
    if (res?.result?.sessionId) {
      lastSessionId = res.result.sessionId;
      log('captured', { sessionId: lastSessionId });
    }
  }
  await sleep(steps.tail || 3000);
  child.stdin.end();
  child.kill('SIGTERM');
  await sleep(500);
  process.exit(0);
})();
