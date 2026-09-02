# ACP probe

The harness that produced [PROTOCOL-FINDINGS.md](../../docs/PROTOCOL-FINDINGS.md) and the [golden fixtures](../../core/src/test/resources/fixtures/). Node, no dependencies.

It exists so that every claim in those documents can be re-derived rather than trusted. When `kiro-cli` ships a new version, re-run it: the diff against the committed fixtures *is* the protocol changelog Kiro doesn't publish.

## Usage

```bash
node probe.mjs <out.jsonl> <script.json> -- --agent-engine v3 --auth-method cli
```

Everything after `--` is passed to `kiro-cli acp`. **Pass `--agent-engine v3 --auth-method cli`** unless you are specifically testing the default engine — without it the agent is local-only and cannot see cloud sessions.

The probe spawns the CLI, plays the script's JSON-RPC calls in order, and logs every frame in both directions to JSONL with `{t, dir, frame}`. `dir` is `out`, `in`, `stderr`, `step-result`, `captured` or `exit`.

Set `PROBE_CWD` to control the agent's working directory (defaults to `$HOME`).

### Scripts

A script is an array of steps:

```jsonc
[
  {"method": "initialize", "params": { … }, "timeout": 25000},
  {"method": "session/new", "params": {"cwd": "./ws", "mcpServers": []}},
  {"method": "session/prompt", "params": {"sessionId": "__SESSION__", "prompt": [ … ]}},
  {"sleep": 3000}
]
```

- `__SESSION__` anywhere in `params` is replaced with the most recent `sessionId` returned by an earlier step — so create-then-prompt works in one run.
- `timeout` defaults to 30 s. A step that times out is logged and the script continues.
- Server-initiated requests are auto-answered so a run cannot wedge. **Permission requests are auto-*rejected*** — deliberately, so a probe can never authorise a tool call. Change `autoAnswer` if you need the accept path.

The scripts in [`scripts/`](scripts/) are the ones that produced the committed fixtures. `load.json` needs a real cloud `sessionId` substituted before it will do anything — get one by running `list3.json` first.

## Regenerating fixtures

```bash
node mkfixtures.mjs <dir-of-raw-jsonl-logs> ../../core/src/test/resources/fixtures
```

It expects the raw logs to be named as the fixture script comments describe (`init-v3.jsonl`, `list2.jsonl`, `list3.jsonl`, `load.jsonl`, `perm-local.jsonl`).

Redaction is pattern-based — repository names, emails, home paths, session titles, OAuth codes, MCP server identities. **Grep the output before committing.** A new field in a future CLI version could carry something the patterns don't know to catch.

## What this is not

A client. It has no reconnect logic, no state model, and no error handling worth the name. It is a listening device pointed at a documented local interface, and it should stay that small — the real client is [F-04](../../docs/FEATURES.md#f-04--acp-protocol-layer-in-core--m-).

It also only ever talks to `kiro-cli` on the local machine over stdio. Pointing anything here at a Kiro network endpoint would violate [ADR-001 §3](../../docs/adr/ADR-001-cloud-session-access.md#3-decision).
