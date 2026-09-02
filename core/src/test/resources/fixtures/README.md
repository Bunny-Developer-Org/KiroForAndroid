# Golden ACP fixtures

Real JSON-RPC frames captured from `kiro-cli 2.19.2` (KAS 0.52.1) on 2026-09-02, as the F-01 spike deliverable. The analysis that goes with them is [docs/PROTOCOL-FINDINGS.md](../../../../../docs/PROTOCOL-FINDINGS.md).

These exist so [F-04](../../../../../docs/FEATURES.md#f-04--acp-protocol-layer-in-core--m-) can be tested against frames Kiro actually sent, not frames we imagined. If a test here starts failing after a CLI upgrade, that is the fixture doing its job: the protocol moved, and the docs say it may.

## Format

JSONL. The first line is a header (`_fixture`, `_note`); every line after it is one wire frame:

```jsonc
{ "dir": "client->agent" | "agent->client", "frame": { /* raw JSON-RPC */ } }
```

Frames are in observed order, so a fixture can be replayed as a script against a fake transport.

## Files

| File | Contents |
|---|---|
| `initialize-v3.jsonl` | The handshake, including the `_meta.kiro` block that names the extension methods, session sources, list scopes and execution targets. |
| `session-list-remote.jsonl` | `session/list` dispatched to the cloud store via `params._meta.kiro.{sessionSource,listScope}`, and the same call scoped to `all`. |
| `source-providers.jsonl` | `_kiro/sourceProviders/list` and `/listResources` — the repository catalog behind a cloud session create. |
| `session-load-remote-head.jsonl` | `session/load` against a `cloud-sandbox` session and the head of its history replay. Truncated to 60 frames; the live run replayed 991. |
| `prompt-turn-with-permission.jsonl` | One complete turn: `turn_start`, `tool_call`, a server-initiated `session/request_permission` answered with `reject`, `tool_call_update`, streamed `agent_message_chunk`s, `turn_end` with `stopReason`, and the per-turn credit summary. |

## Capture conditions

All frames came from:

```bash
kiro-cli acp --agent-engine v3 --auth-method cli
```

The `--agent-engine v3` part is not optional — the default engine cannot reach cloud sessions at all. See PROTOCOL-FINDINGS §2.

## Redaction

Applied by [`tools/acp-probe/mkfixtures.mjs`](../../../../../tools/acp-probe/mkfixtures.mjs), which is the only supported way to regenerate these:

- repository owner/name pairs → stable `example-org/repo-N` aliases, consistent across files
- account email → `user@example.com`; home and workspace paths → `/home/user/...`
- session titles → `Example session`; `cwd` and `workspacePaths` → `/home/user/workspace`
- OAuth codes, device user codes and `token=`/`code=` query values → `REDACTED`
- the capturing user's MCP server names and endpoints → `example-mcp` / `mcp.example.com`
- message text in the cloud replay → a placeholder (the frame *shape* is the point; the conversation was private)

Message text in `prompt-turn-with-permission.jsonl` is kept, because that turn was written for the spike and contains nothing private.

**Before committing a regenerated fixture, grep it.** The redaction is pattern-based and a new field could carry something it does not know to catch.

## Regenerating

```bash
node tools/acp-probe/probe.mjs out.jsonl tools/acp-probe/scripts/<script>.json -- --agent-engine v3 --auth-method cli
```

Then run `mkfixtures.mjs` over the raw logs. `scripts/load.json` needs a real cloud `sessionId` substituted in first — get one from `session-list-remote`'s call.
