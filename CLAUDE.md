# CLAUDE.md

**Read [AGENTS.md](AGENTS.md).** It is the orientation document for this repo — what the project is, where every module and directory lives, the real build/test commands, which docs to read in which order, the conventions CI actually enforces, and the pitfalls that have already bitten someone.

Nothing is duplicated here on purpose: one file, kept current, beats two that drift.

Three things worth knowing before you open anything else, expanded in AGENTS.md:

- **[ADR-001](docs/adr/ADR-001-cloud-session-access.md) constrains everything.** Reverse-engineering Kiro's private cloud API is rejected on principle, not on difficulty.
- **`core/` must not import `android.*` or `androidx.*`.** A Gradle task fails the build if it does.
- **Say what you verified, and when.** Every document and script in this repo states its own verification status inline. Match that; don't write a confident claim you didn't check.
