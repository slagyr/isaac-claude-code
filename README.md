# 🍏 Isaac Claude Code 🤖

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-claude-code/main/isaac-claude-code.png" alt="isaac-claude-code" style="margin-right: 20px; margin-bottom: 10px;">

Claude CLI provider module for [Isaac](https://github.com/slagyr/isaac). Isaac
owns the prompt, transcript, and tool loop; the local `claude` binary is the
completion engine.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Contributes the
`:claude-cli` LLM API and a `POST /claude/turns/:id` MCP route.

<br>

[![Claude Code](https://github.com/slagyr/isaac-claude-code/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-claude-code/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- `:isaac.agent/llm-api` factory `:claude-cli` (`isaac.llm.api.claude-cli/make`).
- Provider template `:claude-code` (command, streaming, tool-loop driver).
- HTTP MCP route `POST /claude/turns/:id`.
- Specs and Gherkin for the CLI provider; real-binary smoke is opt-in.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-http/
  isaac-claude-code/   # this repo
```

```sh
bb spec       # unit specs
bb features   # acceptance features
bb ci         # specs + features
```

From the JVM:

```sh
clj -M:spec
clj -M:features
```

The real-binary smoke remains gated: `bb smoke` (or
`spec/isaac/llm/claude_cli_real_spec.clj`).

## Consumer coordinate

```clojure
io.github.slagyr/isaac-claude-code {:local/root "../isaac-claude-code"}
;; or {:git/url "https://github.com/slagyr/isaac-claude-code.git" :git/sha "..."}
```
