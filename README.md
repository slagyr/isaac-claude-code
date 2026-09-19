# 🤖 Isaac Claude Code

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-claude-code/main/isaac-claude-code.png" alt="isaac-claude-code" style="margin-right: 20px; margin-bottom: 10px;">

Claude CLI provider module for Isaac.

This module contributes:

- `:isaac.agent/llm-api` entry `:claude-cli`

The runtime behavior is the extracted `claude-cli` provider previously shipped in
`isaac-agent`: Isaac owns the prompt, transcript, and tool loop; the local
`claude` binary is used as the completion engine.

The built-in `:claude` provider template and the related provider schema keys
remain in `isaac-agent` for the pure-move train.

<br clear="left">

## Development

```bash
bb spec
bb features
```

The real-binary smoke remains gated in `spec/isaac/llm/claude_cli_real_spec.clj`.
