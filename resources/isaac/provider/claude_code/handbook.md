# isaac.provider.claude-code — Isaac's operating handbook, the Claude Code CLI provider

You are a crew running inside Isaac. This chapter covers what
**isaac-claude-code** owns: the `claude-code` provider type (a `providers`
table entry that shells out to the local `claude` binary instead of calling
an HTTP API directly), how a turn drives that process, its usage accounting,
and how a stale subscription login shows up. If you haven't read
`isaac.foundation` yet (config mechanics, `handbook__configure` itself), read
that first. Crews, models, the provider/model table shape, effort, and the
turn/tool-loop machinery in general belong to `isaac.agent` — this chapter
names them once and moves on; its own topic id is `isaac.provider.claude-code`
and each `##` heading below is also addressable on its own, e.g.
`isaac.provider.claude-code#usage-accounting-and-the-gauge`.

This module contributes no CLI command of its own — every wire it owns (the
per-turn MCP transport, the `POST /claude/turns/:id` route) is internal
plumbing between Isaac and the `claude` binary, never an operator-facing
subcommand. Everything below is either a config path you change with
`handbook__configure`, or something you can only observe (logs, turn
results) and report.

## The claude-code provider type

**What it is.** A `providers` table entry becomes a Claude Code provider by
setting `type: claude-code` — it inherits a template (`api: claude-cli`,
`auth: none`, `command: claude`, `stream-supports-tool-calls: false`,
`drives-tool-loop?: true`) the same way any provider inherits from a
manifest-declared template (`isaac.agent`, Providers, models, and effort).
The entity's own **id** is whatever you name it — it does not have to be
`claude-code` itself; a provider named `harbor` with `type: claude-code`
drives turns exactly like one literally named `claude-code`, and that
distinction matters once you have more than one (see Multiple subscriptions,
below). Fields this module adds on top of the generic provider schema:

- `drives-tool-loop?` (boolean) — when true (the template default), Claude
  Code runs its own native tool loop against Isaac's tools over MCP, and
  this provider installs itself as the turn's `LoopDriver`; when false, the
  provider is a plain shell-out completion engine and Isaac's own tool loop
  parses `<tool_call>` text (see Tool-call syntax, below, and
  `isaac.agent#turns-and-the-tool-loop` for the loop itself).
- `command` (string, generic field, not claude-code-specific) — path to the
  `claude` binary; defaults to `claude` on `PATH`.
- `env` (map) — a literal environment overlay for the spawned `claude`
  process, merged **on top of** everything else. This is how a second
  subscription gets its own login: set `CLAUDE_CONFIG_DIR` here.
- `forward-env` (list of strings, default `["CLAUDE_CODE_OAUTH_TOKEN"]`) —
  names copied from Isaac's own resolved environment (process env or
  `<root>/.env`) into the child process; a missing or blank name is silently
  omitted, and `ANTHROPIC_API_KEY` is never forwarded even if you list it
  explicitly. An empty list (`[]`) forwards nothing.

**How to change it.**

```
config set providers.claude-code.type claude-code
config set providers.claude-code.command claude
config set providers.claude-code.drives-tool-loop? true
```

**How to verify.** `config get providers.claude-code` (or
`` `config:providers.claude-code.drives-tool-loop?` `` through
`handbook__read`) shows the resolved entry, template defaults included.
Point a `models` table entry's `provider` at this id and send a turn — the
model string on that entry is passed straight through as `claude --model
<string>`; Isaac never validates or hardcodes what names your `claude`
build accepts, so use whatever alias or full model id your CLI recognizes.

### Troubleshooting

- **A turn on this provider never seems to call any tools.** Check
  `drives-tool-loop?` — with it false, the provider is a bare completion
  engine and tool calls only happen if the model writes the `<tool_call>`
  text protocol and Isaac's own loop parses it.
- **You expect `isaac auth login --provider claude-code` to do something and
  it doesn't.** This template's `auth` is `none` — Isaac never manages this
  provider's credential. Login lives entirely in the `claude` binary itself,
  outside Isaac: run `claude` interactively (first run) or `/login` inside an
  already-open session to refresh the standard OAuth pair in
  `~/.claude/.credentials.json`. `claude setup-token` is the alternative for
  an unattended deployment — it mints a long-lived token for the
  `CLAUDE_CODE_OAUTH_TOKEN` environment variable instead of a session login,
  good for about a year rather than the standard pair's short refresh-token
  window.
- **Two provider ids both say `type: claude-code` and you're not sure which
  is "the real one."** Neither is — `type` selects the template, the
  entity's own id is just a label. Check `command`/`env` on each entry, not
  the id, to know which subscription a given provider actually drives.

## Multiple subscriptions

**What it is.** `CLAUDE_CONFIG_DIR` is the `claude` CLI's own way of
isolating a *login*, not just settings — two provider entries with
different `env.CLAUDE_CONFIG_DIR` values drive two independent `claude`
subscriptions from the same Isaac install, each reachable through its own
provider id and, through that, its own crew(s). `env` is a literal overlay:
it applies after the server's inherited environment and after
`forward-env`'s named copies, so an `env` value always wins over a same-named
forwarded one — and `ANTHROPIC_API_KEY` is stripped after both merges no
matter which one supplied it.

**How to change it.**

```
config set providers.claude-a.type claude-code
config set providers.claude-a.env.CLAUDE_CONFIG_DIR /path/to/subscription-a
config set providers.claude-b.type claude-code
config set providers.claude-b.env.CLAUDE_CONFIG_DIR /path/to/subscription-b
```

Point separate `models` entries at `claude-a` / `claude-b`, and separate
crews at those models — each crew now drives its own subscription.

**How to verify.** There's no config-path introspection into which
subscription actually answered; verify by behavior (a crew answering with
context specific to one login) or, for an operator, by checking each
`CLAUDE_CONFIG_DIR`'s own `claude` session state directly (CLI-only, outside
Isaac).

### Troubleshooting

- **`config validate` (or a turn) warns `:claude/oauth-token-missing`.** This
  fires once per provider build when `CLAUDE_CODE_OAUTH_TOKEN` is in
  `forward-env` (the default) but isn't actually set anywhere Isaac can see
  it — expected if this provider relies entirely on `CLAUDE_CONFIG_DIR`
  instead of a forwarded token; harmless otherwise. Set the token in the
  root's `.env` or drop it from `forward-env` if you don't use it.
- **A provider's `env.ANTHROPIC_API_KEY` doesn't reach the process.** By
  design — the key is stripped after every merge, on every claude-code
  provider, unconditionally; there is no override for this.
- **Subscription A's login leaks into subscription B's turns (or
  vice-versa).** Confirm each provider's `env.CLAUDE_CONFIG_DIR` is actually
  distinct and set — `config get providers.<id> --raw` shows the literal
  value, not a template default.

## How a turn drives the claude process

**What it is.** Every turn against this provider spawns **exactly one**
`claude` process for its whole duration — a turn's tool-call cycles never
respawn it. With `drives-tool-loop? true` the process runs `--print
--input-format stream-json --output-format stream-json --strict-mcp-config
--permission-mode bypassPermissions --mcp-config <path> --tools ""
--no-session-persistence`, where `<path>` names a tiny, per-turn MCP config
naming an HTTP server at `http://127.0.0.1:<ephemeral-port>` with a random
bearer nonce — Claude Code's own Streamable-HTTP MCP client talks to that
loopback listener directly, no bridge process in between. The listener (and
the turn's tool registry behind it) exists only for that one turn's
lifetime and is torn down when the turn ends. Isaac's tools are exposed
there under their own names (`exec__run`, `fs__read`, …); Claude Code
prefixes them `mcp__isaac__<name>` internally, and the driver strips that
prefix back off before recording the call, so the transcript always shows
the Isaac-native name. Every tool call still executes through the turn's
own tool function — nothing about *what* a tool call does changes because
Claude Code, not Isaac's default loop, is driving.

With `drives-tool-loop? false` (or once the driven path has fallen back —
see below), the process instead runs `--print --output-format json --tools
"" --no-session-persistence`, Isaac's own text `<tool_call>` protocol is
taught in the system prompt, and Isaac's default tool loop parses and
executes whatever the model wrote as text.

Isaac, not the CLI, owns the transcript and history: `--no-session-persistence`
is always set, and each call replays prior turns as plain text inside the
stream-json user envelope (assistant history becomes "Assistant: …" prose,
since stream-json input only accepts user-role envelopes) rather than
relying on the CLI's own session/`--resume` machinery.

**Falling back to the fence path.** A driven turn silently drops to the
non-driven, text-protocol path — logged once as `:claude/driver-fallback`
with a `:reason` — when: the CLI exits before emitting a first stream event
(`:cli-start-failed`); the run ends with no result event or one reporting
`is_error` (`:cli-error`); the MCP server the CLI reports for itself in its
init event comes back `failed`, or `connected` with zero tools
(`:mcp-failed`); or the fake-CLI test hook simulates an MCP-init failure
(`:mcp-init`). A `pending` MCP status at init is **not** a failure — Claude
Code can emit its init event before the tool server answers, and the turn
proceeds normally once tools do arrive. Once a provider has fallen back for
a turn, it stops claiming the tool loop for that call so Isaac's own loop
picks up any tool calls the model still tries to make as text.

**The title side-call.** There is no documented `claude` flag to suppress
its own per-invocation title-generation side call even with
`--no-session-persistence`; every driven turn logs `:claude/title-side-call`
(`:found false`) once, naming the accepted cost rather than hiding it.

**How to verify.** `isaac logs` (`isaac.foundation`) for `:claude/mcp-status`
(the CLI's own report of Isaac's own MCP server and tool count),
`:claude/driver-exit` (one line per driven turn: exit code, whether a result
event arrived, cycle count, and token totals), and `:claude/driver-fallback`
when a turn dropped to the fence path.

### Troubleshooting

- **A turn fell back to the fence path and you want to know why.** Read the
  `:claude/driver-fallback` log entry's `:reason` and `:stderr` — it always
  carries the CLI's own stderr or the result event's error text, never a
  guess.
- **The transcript shows a tool call under a name like
  `mcp__isaac__exec__run`.** It shouldn't reach the transcript that way —
  the driver strips the `mcp__isaac__` prefix before recording. Seeing the
  raw prefixed name anywhere is a bug to report, not expected behavior.
- **A crew's quarters accumulate a stray title/session artifact.** That's
  the title side-call's known cost (above) — not a leak to chase down.
- **Multiple tool calls in one cycle look like they respawned the CLI.**
  They didn't — one process serves every cycle of a turn; check
  `:claude/driver-exit`'s `:cycles` count against the transcript's own tool
  calls to confirm.

## Usage accounting and the gauge

**What it is.** The real CLI puts each cycle's own prompt size on that
cycle's assistant message and the **sum** of every cycle's prompt size on
the turn's single trailing result event — the result's usage is turn
**spend**, never a prompt size to store. Isaac's session gauge (the running
estimate the next turn starts from — `isaac.agent`, Compaction and context
modes) is stamped from the **first** cycle's prompt size instead, because
that's the prompt the next Isaac turn actually repeats; the sums across all
cycles still add up into the turn's total token usage recorded on the
session. A CLI that reports its tool calls only in its final result (rather
than as they happen) leaves the driver to replay them as cycles after the
fact — those replayed cycles never actually reached the model, so they
stamp zero usage each; only the one cycle that really ran the model stamps
the gauge. A figure larger than the model's context window is never stored
as the gauge — it's a turn total that leaked through, not a prompt size, and
is dropped rather than recorded.

**How to verify.** `isaac sessions show <id>` (`isaac.agent`) reports the
session's current token figures. `:claude/driver-exit`'s
`input-tokens`/`cache-read-tokens`/`cache-write-tokens`/`output-tokens`
fields are the turn's summed cost across every cycle, independent of which
cycle became the gauge.

### Troubleshooting

- **The session's stored context size looks far smaller than what the CLI's
  result event reported.** Expected — the gauge is the *first* cycle's
  prompt size on a multi-cycle turn, not the result's turn-total sum. Check
  `:claude/driver-exit` for the full picture.
- **A turn that hit a session limit mid-way still shows nonzero usage for
  the cycles before the wall.** By design — cycles that finished before a
  wall are not free just because the last request failed; their sums are
  kept and logged, and the gauge still reflects the first cycle's prompt
  size.
- **The gauge shows a suspiciously huge number (bigger than the model's
  context window).** Should not happen — a figure above the window is
  filtered out before it's stored. If you see one anyway, that's a bug to
  report (`:session/stamp-implausible` is the guard's own log line when it
  catches this in isaac.agent).

## Tool-call syntax on the fence path

**What it is.** On the non-driven (fence) path, the contract is
`<tool_call>{"name":"<tool>","arguments":{...}}</tool_call>` in the model's
own text. Claude's native `<invoke name="...">` / `<parameter
name="...">` blocks and a bare `{"name":...,"arguments":...}` inside a
markdown code fence are both also accepted and executed the same way — this
covers real, observed model drift, not just the literal contract text. A
call-shaped block (anything that looks like it's trying to be one of these
three forms) that still fails to parse gets **exactly one** corrective
re-prompt telling the model to use the literal `<tool_call>` form; a second
failure ends the turn with `:error :tool-protocol` rather than guessing at
a verdict from broken text. `:tool-protocol` also carries `:unavailable?
true` with a one-minute `:retry-after-ms`, so a hail delivered into it
defers instead of burning a delivery attempt or counting toward
dead-letter (`hails-never-die`).

### Troubleshooting

- **A turn ended with `:error :tool-protocol`.** The model emitted a
  call-shaped block twice in a row that couldn't be parsed either time —
  check the log for `:claude-cli/tool-syntax-drift` (the first, warned
  attempt) followed by `:claude-cli/tool-protocol` (the second, logged as
  an error). This is a model-output problem, not a config one.
- **Text that looks like a stray, half-finished tool call shows up in the
  transcript.** It shouldn't — text following a successfully parsed call
  block is deliberately not persisted as assistant content, to avoid
  recording a model's fabricated narration of what it thinks the result
  will be.

## Auth failures and login expiry

**What it is.** This provider classifies what the CLI itself says about a
failed run, never the plain conversational transcript: stderr and the
result event's own error text are checked against known auth phrases
(`not logged in`, `please run /login`, `invalid api key`, `oauth session/
token expired`, `failed to authenticate`, `no credentials`) and known
limit phrases (`session limit`, `usage limit`, `rate limit`, `too many
requests`, `quota exceeded`, `credit balance`). A driven turn that hits
either mid-stream is **weather**, not a fence-fallback trigger or a hard
failure — re-running the same request without MCP would only meet the same
wall. A limit classifies `:rate-limited`/`:wall`; a login failure
classifies `:auth-failed`/`:auth`. On the older non-driven path, the same
auth phrases (plus a bare "unauthorized") mark the response
`:unavailable? true :reason :auth` so a delivered hail defers rather than
dead-lettering. What actually happens next with that classification —
suspending the turn, scheduling a resume, posting an attention bulletin —
is the drive's job, not this provider's; see `isaac.agent#turns-and-the-
tool-loop` and `isaac.agent#comms-and-delivery`.

**The quiet failure mode.** A subscription login that has simply expired
doesn't always produce one of the phrases above — sometimes the CLI just
answers with nothing usable at all. Isaac's own guard for that shape (a
terminal response with no content, after one continuation retry) ends the
turn `:empty-terminal-response`; the drive treats **every** turn that ends
that way as weather too, reason `:silence`, parked and retried on the same
backoff as any other wall rather than logged as a hard error. In practice:
a `claude` login that has expired tends to show up as most or every turn on
that provider ending `:empty-terminal-response` in a row. Only a human can
fix this — Isaac has no automated re-login path for an `auth: none`
provider; an operator has to re-authenticate the `claude` binary directly.

**How to verify.** `isaac logs server` (or `cli`) for the turn's own log
line; `isaac sessions show <id>` (`isaac.agent`) shows a suspended turn
marker with its stamped reason (`:wall`, `:auth`, or `:silence`) and
`retry-at`.

### Troubleshooting

- **Every turn on one provider ends `:empty-terminal-response`, or that
  provider's sessions keep suspending with reason `:silence`.** Treat it as
  an expired `claude` login first — probe the binary directly (with the
  same environment Isaac gives it, including any `CLAUDE_CONFIG_DIR`) and
  have a human re-authenticate it: `claude` (first run) or `/login` inside an
  open session re-does the standard OAuth pair; `claude setup-token` mints a
  long-lived `CLAUDE_CODE_OAUTH_TOKEN` instead, which `subprocess-env`
  forwards to the CLI automatically once it's set in Isaac's own process
  environment — note that a running Isaac process won't pick up a newly set
  env var until it is itself restarted with it present.
- **A turn suspended with reason `:wall` and a `retry-at` in the near
  future.** That's a session/usage limit, not a login problem — nothing to
  fix; it should resume on its own once the window the CLI named reopens.
- **You expect a hard `:error` for a login failure and instead see the
  turn quietly suspended.** That's the intended behavior since the
  silence-is-weather ruling — a suspended turn with an attention bulletin
  is the correct signal, not a bug; check attention delivery
  (`isaac.agent#comms-and-delivery`) if the bulletin itself seems missing.
