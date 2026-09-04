# 🤖 Isaac Claude Code

Claude CLI provider module for Isaac.

This module contributes:

- `:isaac.agent/llm-api` entry `:claude-cli`

The runtime behavior is the extracted `claude-cli` provider previously shipped in
`isaac-agent`: Isaac owns the prompt, transcript, and tool loop; the local
`claude` binary is used as the completion engine.

The built-in `:claude` provider template and the related provider schema keys
remain in `isaac-agent` for the pure-move train.

## Development

```bash
bb spec
bb features
```

The real-binary smoke remains gated in `spec/isaac/llm/claude_cli_real_spec.clj`.
