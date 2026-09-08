Feature: Claude Code drives the tool loop against isaac's MCP tools (isaac-5xn7)

  With `:drives-tool-loop? true` on the claude provider, a turn spawns one
  `claude` process (stream-json in/out, no claude tools, `--mcp-config`
  pointing at isaac's mcp-bridge for THIS turn) and lets Claude Code run the
  native tool loop. Every tool call still executes through the drive's tool
  function via the per-turn MCP registry (isaac-zocg), so the transcript,
  comm events, and per-cycle token stamps are identical to the default loop
  (isaac-1sdl). Isaac owns the transcript: one process per turn, history
  replayed as text, thinking persisted as reckoning, compaction between
  turns only.
  Decisions (2026-09-03, Micah): one CLI process per turn; thinking blocks
  persist as reckoning (excluded from prompt and recall); title side call —
  find the switch or log its cost; fallback to the fence path for a turn
  when the CLI lacks stream-json input or MCP init fails, logged, never a
  failed turn.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/providers/claude.edn" exists with:
      | path              | value  |
      | command           | claude |
      | drives-tool-loop? | true   |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value  |
      | model    | sonnet |
      | provider | claude |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | sub-sonnet  |
      | soul  | Think hard. |
    And the crew "thinker" allows tools: "exec/run"
    And the following sessions exist:
      | name | crew    |
      | main | thinker |

  Scenario: a turn with one tool call persists the pair, the reply, and the final cycle's usage
    Given a fake Claude Code on the path scripted with:
      | cycle | kind     | payload                                                              |
      | 1     | tool_use | {"name":"exec__run","input":{"command":"echo hi"}}                  |
      | 1     | usage    | {"input_tokens":200,"cache_read_input_tokens":50,"cache_creation_input_tokens":10} |
      | 2     | text     | hi came back                                                         |
      | 2     | usage    | {"input_tokens":260,"cache_read_input_tokens":60,"cache_creation_input_tokens":0}  |
    When the user sends "run it" on session "main"
    Then the response is "hi came back"
    And session "main" has transcript matching:
      | type       | message.role | message.content | name      |
      | toolCall   |              |                 | exec__run |
      | toolResult |              | #"hi"           |           |
      | message    | assistant    | hi came back    |           |
    And the following sessions match:
      | name | last-input-tokens | turn-input-tokens |
      | main | 320               | 580               |
    And the log has entries matching:
      | event             | provider | driver   |
      | :turn/loop-driver | claude   | provider |

  Scenario: a multi-cycle turn persists pairs in feed order
    Given a fake Claude Code on the path scripted with:
      | cycle | kind     | payload                                             |
      | 1     | tool_use | {"name":"exec__run","input":{"command":"echo one"}} |
      | 2     | tool_use | {"name":"exec__run","input":{"command":"echo two"}} |
      | 3     | text     | both done                                           |
    When the user sends "twice" on session "main"
    Then session "main" has transcript matching:
      | type       | message.role | message.content |
      | toolCall   |              | #"echo one"     |
      | toolResult |              | #"one"          |
      | toolCall   |              | #"echo two"     |
      | toolResult |              | #"two"          |
      | message    | assistant    | both done       |
    And the memory comm has events matching:
      | event       | cycle |
      | cycle-start | 1     |
      | cycle-start | 2     |
      | cycle-start | 3     |

  Scenario: text and thinking deltas reach the comm in order; thinking persists as reckoning and stays out of the next prompt
    Given a fake Claude Code on the path scripted with:
      | cycle | kind     | payload                 |
      | 1     | thinking | weighing the options    |
      | 1     | text     | here is my answer       |
    When the user sends "think then answer" on session "main"
    Then the memory comm has events matching:
      | event     | text                 |
      | reckoning | weighing the options |
      | chatter   | here is my answer    |
    And session "main" has transcript matching:
      | type      | message.role | message.content      | text                 |
      | reckoning |              |                      | weighing the options |
      | message   | assistant    | here is my answer    |                      |
    When the user sends "and again" on session "main"
    Then the fake Claude Code received on stdin:
      | role      | content                     |
      | user      | think then answer           |
      | assistant | here is my answer           |
      | user      | and again                   |

  Scenario: cancel mid-loop terminates the process and the turn ends cancelled
    Given a fake Claude Code on the path scripted with:
      | cycle | kind     | payload                                             |
      | 1     | tool_use | {"name":"exec__run","input":{"command":"sleep 30"}} |
      | 2     | text     | never sent                                          |
    When the user sends "slow" on session "main"
    And the turn is cancelled on session "main" after 1 tool call
    Then the turn result is:
      | key    | value      |
      | status | :cancelled |
    And the fake Claude Code was terminated
    And session "main" has transcript matching:
      | type     | message.role | message.content |
      | toolCall |              | #"sleep 30"     |
    And the log has entries matching:
      | event                    | provider |
      | :claude/driver-terminated | claude   |

  Scenario: prior turns are replayed as text history before the live prompt
    Given a fake Claude Code on the path scripted with:
      | cycle | kind | payload |
      | 1     | text | first   |
    When the user sends "one" on session "main"
    Given a fake Claude Code on the path scripted with:
      | cycle | kind | payload |
      | 1     | text | second  |
    When the user sends "two" on session "main"
    Then the fake Claude Code received on stdin:
      | role      | content |
      | user      | one     |
      | assistant | first   |
      | user      | two     |
    And the fake Claude Code was invoked with:
      | arg                       | value       |
      | --input-format            | stream-json |
      | --output-format           | stream-json |
      | --tools                   |             |
      | --strict-mcp-config       |             |
      | --no-session-persistence  |             |

  Scenario: an older CLI or a failed MCP init falls back to the fence path for that turn
    Given a fake Claude Code on the path scripted with:
      | cycle | kind | payload |
      | 1     | text | fenced  |
    And the fake Claude Code fails MCP initialization
    When the user sends "fallback" on session "main"
    Then the response is "fenced"
    And the log has entries matching:
      | event                   | provider | reason   |
      | :claude/driver-fallback | claude   | mcp-init |
    And the fake Claude Code was invoked with:
      | arg              | value |
      | --print          |       |
      | --output-format  | json  |

  Scenario: a CLI that exits before its first stream event falls back to the fence path with the stderr logged (isaac-nni3)
    Given a fake Claude Code on the path scripted with:
      | cycle | kind | payload |
      | 1     | text | fenced  |
    And the fake Claude Code exits 1 before streaming with stderr "Error: When using --print, --output-format=stream-json requires --verbose"
    When the user sends "fallback" on session "main"
    Then the response is "fenced"
    And the log has entries matching:
      | event                   | provider | reason           | stderr                           |
      | :claude/driver-fallback | claude   | cli-start-failed | #"stream-json requires --verbose" |
    And the fake Claude Code was invoked with:
      | arg              | value       |
      | --output-format  | stream-json |
      | --verbose        |             |
    And the fake Claude Code was invoked with:
      | arg              | value |
      | --print          |       |
      | --output-format  | json  |

  @wip
  Scenario: text that arrives only as content_block_delta stream events becomes the reply (isaac-0lyh)
    Claude Code 2.1.236 with --include-partial-messages emits the reply as
    stream_event/content_block_delta/text_delta chunks, then message events,
    then one result event with stop_reason end_turn and usage. The driver
    must build the reply from those shapes, not only from a final text block.
    Given a fake Claude Code on the path scripted with:
      | cycle | kind        | payload                                                                                       |
      | 1     | text_delta  | po                                                                                            |
      | 1     | text_delta  | ng                                                                                            |
      | 1     | usage       | {"input_tokens":2,"cache_read_input_tokens":3289,"cache_creation_input_tokens":5473}           |
    When the user sends "Reply with exactly: pong" on session "main"
    Then the response is "pong"
    And session "main" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | pong            |
    And the following sessions match:
      | name | last-input-tokens |
      | main | 8764              |
    And the log has entries matching:
      | event               | provider | exit-code | result-event |
      | :claude/driver-exit | claude   | 0         | true         |

  @wip
  Scenario: a result event with is_error, or an exit with no result event, falls back with the CLI's stderr logged
    Given a fake Claude Code on the path scripted with:
      | cycle | kind         | payload                               |
      | 1     | error_result | MCP server "isaac" failed to connect  |
    When the user sends "fallback please" on session "main"
    Then the log has entries matching:
      | event                   | provider | reason    | stderr                        |
      | :claude/driver-exit     | claude   |           |                               |
      | :claude/driver-fallback | claude   | cli-error | #"(?s).*failed to connect.*" |
    And the fake Claude Code was invoked with:
      | arg              | value |
      | --print          |       |
      | --output-format  | json  |
