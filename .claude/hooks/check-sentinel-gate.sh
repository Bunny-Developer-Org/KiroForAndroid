#!/usr/bin/env bash
# Claude Code PreToolUse hook: interrupts a `git commit` Bash tool call when the
# pending change set touches non-doc paths and no recent Sentinel review
# evidence exists under .sentinel/reviews/. See AGENTS.md "Sentinel quality
# gate" and .agents/skills/sentinel/SKILL.md for the mandatory contract this
# enforces. It is the Claude Code counterpart of
# .cursor/hooks/check-sentinel-gate.sh — same heuristic, different tool schema.
#
# Claude Code schema (differs from Cursor):
#   Input  (stdin JSON): { "tool_name": "Bash",
#                          "tool_input": { "command": "...", ... },
#                          "cwd": "..." }
#   Output (stdout JSON): { "hookSpecificOutput": {
#                             "hookEventName": "PreToolUse",
#                             "permissionDecision": "allow"|"ask"|"deny",
#                             "permissionDecisionReason": "..." } }
#
# Fails open on any unexpected error: a bug in this script must never block a
# legitimate commit, it may only *ask* for confirmation when applicable.
set -u

allow() {
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}\n'
  exit 0
}

# Any unexpected failure below falls through to allow (fail-open).
trap 'allow' ERR

input="$(cat)"

# Only gate Bash tool calls; every other tool is irrelevant here.
tool_name="$(printf '%s' "$input" | jq -r '.tool_name // empty' 2>/dev/null || true)"
[ "$tool_name" = "Bash" ] || allow

command_str="$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null || true)"
cwd="$(printf '%s' "$input" | jq -r '.cwd // empty' 2>/dev/null || true)"

[ -z "$command_str" ] && allow
printf '%s' "$command_str" | grep -Eq '(^|[;&|]|\s)git\s+commit(\s|$)' || allow

[ -z "$cwd" ] && cwd="$(pwd)"
root="$(git -C "$cwd" rev-parse --show-toplevel 2>/dev/null || true)"
[ -z "$root" ] && allow

changed="$(
  {
    git -C "$root" diff --cached --name-only 2>/dev/null
    git -C "$root" diff --name-only 2>/dev/null
  } | sort -u
)"
[ -z "$changed" ] && allow

# Paths that never require Sentinel: documentation, process/governance
# artifacts, and the doctrine/skill stacks themselves are out of scope for
# "bug fixes, feature improvements, behavior-changing refactors, schema/API,
# deployment/CI/secret/runtime configuration changes" (SKILL.md "Mandatory
# contract"). Everything else is treated as potentially in-scope. Kept in sync
# with .cursor/hooks/check-sentinel-gate.sh.
exempt_regex='^(docs/|kb/|\.sentinel/|\.cursor/|\.kiro/|\.agents/|doctrine/|\.claude/)|\.md$'

in_scope="$(printf '%s\n' "$changed" | grep -Ev "$exempt_regex" || true)"
[ -z "$in_scope" ] && allow

# Weak but concrete proxy for "Sentinel already reviewed this session's
# work": a review report written recently under .sentinel/reviews/, per the
# skill's own report contract (SKILL.md section 7 "Report").
recent_review="$(find "$root/.sentinel/reviews" -type f -newermt '-6 hours' 2>/dev/null | head -1 || true)"
[ -n "$recent_review" ] && allow

file_list="$(printf '%s\n' "$in_scope" | head -20 | sed 's/^/  - /')"
file_count="$(printf '%s\n' "$in_scope" | wc -l | tr -d ' ')"

reason="This commit touches ${file_count} path(s) outside docs/process artifacts, and no Sentinel review report was found under .sentinel/reviews/ from the last 6 hours. Before completing this commit: does this change set match Sentinel's mandatory contract (bug fix, feature improvement, behavior-changing refactor, schema/API/deployment/CI/secret/runtime config change, or tests proving production behavior) per AGENTS.md and .agents/skills/sentinel/SKILL.md?
In-scope paths:
${file_list}
If yes and Sentinel has not reviewed this exact change set yet, stop, run .agents/skills/sentinel/SKILL.md now, apply P1/in-scope-P2 findings, rerun checks, and include the attestation before finishing. If the change is genuinely out of Sentinel's scope despite touching these paths (e.g. pure docs/comments in source files, generated lockfile noise), state that explicitly, then re-run the commit."

jq -n --arg r "$reason" \
  '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"ask",permissionDecisionReason:$r}}'
exit 0
