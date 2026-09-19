Feature: Claude subscription provider via CLI shell-out

  The `claude` provider uses the local `claude` binary (logged in with
  subscription) for raw completions. Isaac owns the full prompt, transcript,
  and tool loop. The binary is invoked with --print, no claude tools,
  no claude session management.

  Named secrets from <isaac-home>/.env are copied into the child env
  (`:forward-env` on the provider; default CLAUDE_CODE_OAUTH_TOKEN).
  ANTHROPIC_API_KEY is never forwarded. (isaac-1awj)

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/providers/claude.edn" exists with:
      | path              | value  |
      | command           | claude |
      | drives-tool-loop? | false  |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | sub-sonnet  |
      | soul  | Think hard. |
    And the following sessions exist:
      | name | crew    |
      | main | thinker |

  Scenario: non-tool prompt uses raw shell-out
    Given the claude binary is stubbed to return "4"
    When the user sends "What is 2+2?" on session "main"
    Then the response is "4"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | json  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --system-prompt          |       |
      | (system prompt contains soul text) | |
      | (conversation prompt on stdin) | |
      | (user prompt does not contain soul text) | |

  Scenario: streaming response from claude subscription provider
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path                  | value |
      | command               | claude |
      | stream-non-tool-turns | true  |
    And the claude binary is stubbed to stream ["Hello", " ", "world"]
    When the user sends "hi" on session "main"
    Then the response streams as ["Hello", " ", "world"]
    And the claude binary was invoked exactly once with:
      | arg                      | value         |
      | --print                  |               |
      | --output-format          | stream-json   |
      | --include-partial-messages|              |
      | --tools                  |       |
      | --no-session-persistence |               |
      | --model                  | sonnet        |
      | --system-prompt          |               |
      | (conversation prompt on stdin) |      |

  Scenario: tool-using turn with Isaac-managed tools
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | sub-sonnet  |
      | soul  | Think hard. |
    And the crew has tools: [exec]
    And the claude binary is stubbed to first return tool call text for exec, then "done"
    When the user sends "list files" on session "main"
    Then the exec tool is executed
    And the claude binary was invoked exactly twice
    And the second invocation included the tool result serialized in the prompt text
    And the response is "done"

  Scenario: tool protocol contract rides on system prompt authority
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return "ok"
    When the user sends "run a command" on session "main"
    Then the claude binary was invoked exactly once with:
      | arg                      | value |
      | --system-prompt          |       |
      | (system prompt contains protocol contract) | |
      | (user prompt does not contain protocol contract) | |
      | (conversation prompt on stdin) | |

  Scenario: error from claude binary is reported
    Given the claude binary is stubbed to fail with exit code 1 and message "claude: boom"
    When the user sends "hi" on session "main"
    Then an error is reported indicating the claude binary failed
    And the error message contains "claude: boom"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | json  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|

  Scenario: login failure is a loud error and classifies as auth-unavailable
    Given the claude binary is stubbed to fail with exit code 1 and message "Not logged in · Please run /login"
    When the user sends "hi" on session "main"
    Then an error is reported indicating the claude binary failed
    And the error message contains "Please run /login"
    And the error is classified as auth-unavailable

  Scenario: claude subscription provider with custom binary path
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path    | value              |
      | command | /custom/path/claude|
    And the claude binary at "/custom/path/claude" is stubbed to return "42"
    When the user sends "What is 6*7?" on session "main"
    Then the response is "42"
    And the claude binary at "/custom/path/claude" was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | json  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|

  Scenario: extra args from provider config are forwarded
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path        | value         |
      | command     | claude        |
      | extra-args  | ["--foo","bar"]|
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | json  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --foo                    | bar   |

  Scenario: full history passed each turn (Isaac controls transcript)
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path    | value  |
      | command | claude |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the following sessions exist:
      | name | crew    |
      | math | thinker |
    And session "math" has transcript:
      | type    | message.role | message.content    |
      | message | user         | previous turn      |
      | message | assistant    | previous reply     |
    And the claude binary is stubbed to return "42"
    When the user sends "new question" on session "math"
    Then the response is "42"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | json  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --system-prompt          |       |
      | (stdin contains full history) | |
      | (no --continue or --resume) | |

  Scenario: claude subscription provider uses subscription login (no raw API key)
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path    | value  |
      | command | claude |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the following sessions exist:
      | name | crew    |
      | sub  | thinker |
    And the file "~/.claude/.credentials.json" exists with the subscription login
    And ANTHROPIC_API_KEY is not set in the environment
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "sub"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | json  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | (no ANTHROPIC_API_KEY in env) | |

  Scenario: claude subscription provider with extra args from config
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path        | value         |
      | command     | claude        |
      | extra-args  | ["--foo","bar"]|
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value        |
      | model    | sonnet       |
      | provider | claude  |
    And the following sessions exist:
      | name | crew    |
      | main | thinker |
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --print                  |       |
      | --output-format          | json  |
      | --tools                  |       |
      | --no-session-persistence |       |
      | --model                  | sonnet|
      | --foo                    | bar   |

  Scenario: non-stream json output records token usage on the transcript
    Given the claude binary is stubbed to return json with usage "4" and tokens 120 and 15
    When the user sends "What is 2+2?" on session "main"
    Then the response is "4"
    And session "main" has transcript matching:
      | #index | type    | message.role | message.usage.prompt-tokens | message.usage.output-tokens |
      | -1     | message | assistant    | 120                        | 15                          |
    And the claude binary was invoked exactly once with:
      | arg                      | value |
      | --output-format          | json  |

  Scenario: streaming stream-json records terminal usage on the transcript
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path                  | value |
      | command               | claude |
      | stream-non-tool-turns | true  |
    And the claude binary is stubbed to stream-json with terminal usage ["Hi", " there"]
    When the user sends "hi" on session "main"
    Then the response streams as ["Hi", " there"]
    And session "main" has transcript matching:
      | #index | type    | message.role | message.usage.prompt-tokens | message.usage.output-tokens | message.usage.cache-read-tokens | message.usage.cache-write-tokens |
      | -1     | message | assistant    | 46                         | 7                           | 3                        | 1                         |

  Scenario: missing usage in json output still completes with zero usage
    Given the claude binary is stubbed to return json without usage "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And session "main" has transcript matching:
      | #index | type    | message.role | message.usage.prompt-tokens | message.usage.output-tokens |
      | -1     | message | assistant    | 0                          | 0                           |

  @wip
  Scenario: CLAUDE_CODE_OAUTH_TOKEN from .env is forwarded to the claude subprocess
    Given the isaac .env file contains:
      """
      CLAUDE_CODE_OAUTH_TOKEN=marigold-oauth
      """
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                                             | value |
      | --print                                         |       |
      | --output-format                                 | json  |
      | --model                                         | sonnet|
      | (env CLAUDE_CODE_OAUTH_TOKEN is marigold-oauth) |       |

  @wip
  Scenario: an unlisted .env secret is not forwarded to the claude subprocess
    Given the isaac .env file contains:
      """
      CLAUDE_CODE_OAUTH_TOKEN=marigold-oauth
      LONGWAVE_DISCORD_TOKEN=sk-longwave
      """
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                                             | value |
      | --print                                         |       |
      | --output-format                                 | json  |
      | --model                                         | sonnet|
      | (env CLAUDE_CODE_OAUTH_TOKEN is marigold-oauth) |       |
      | (no env LONGWAVE_DISCORD_TOKEN)                 |       |

  @wip
  Scenario: a name listed in forward-env is forwarded to the claude subprocess
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path        | value                                       |
      | command     | claude                                      |
      | forward-env | ["CLAUDE_CODE_OAUTH_TOKEN","SKYBEAM_TOKEN"] |
    And the isaac .env file contains:
      """
      CLAUDE_CODE_OAUTH_TOKEN=marigold-oauth
      SKYBEAM_TOKEN=skybeam-secret
      """
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                                             | value |
      | --print                                         |       |
      | --output-format                                 | json  |
      | --model                                         | sonnet|
      | (env CLAUDE_CODE_OAUTH_TOKEN is marigold-oauth) |       |
      | (env SKYBEAM_TOKEN is skybeam-secret)           |       |

  @wip
  Scenario: ANTHROPIC_API_KEY is stripped even when listed in forward-env
    Given the isaac EDN file "config/providers/claude.edn" exists with:
      | path        | value                                           |
      | command     | claude                                          |
      | forward-env | ["CLAUDE_CODE_OAUTH_TOKEN","ANTHROPIC_API_KEY"] |
    And the isaac .env file contains:
      """
      CLAUDE_CODE_OAUTH_TOKEN=marigold-oauth
      ANTHROPIC_API_KEY=sk-marigold
      """
    And the claude binary is stubbed to return "ok"
    When the user sends "hi" on session "main"
    Then the response is "ok"
    And the claude binary was invoked exactly once with:
      | arg                                             | value |
      | --print                                         |       |
      | --output-format                                 | json  |
      | --model                                         | sonnet|
      | (env CLAUDE_CODE_OAUTH_TOKEN is marigold-oauth) |       |
      | (no ANTHROPIC_API_KEY in env)                   |       |

  # --- isaac-jkx7: tool-call syntax drift on the fence path ------------------
  # The contract is <tool_call>{json}</tool_call>. Models drift: Claude's native
  # <invoke name="X"><parameter name="k">v</parameter></invoke> (opus, 09-03),
  # bare {"name":…,"arguments":…} in a markdown code fence (yopp, 09-17), or a
  # <tool_call> whose JSON does not parse (isaac-work-2, 09-03). A drifted call
  # must never be treated as a reply: parse what can be parsed, and treat any
  # unparsed call-shaped block as a protocol violation — one corrective
  # re-prompt, then :error :tool-protocol. Never a silent verdict.

  Scenario: Claude's native invoke syntax executes the tool exactly like the fence (isaac-jkx7)
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return in sequence:
      | response                                                                                              |
      | <invoke name="exec__run"><parameter name="command">echo drift</parameter></invoke>                     |
      | done                                                                                                  |
    When the user sends "run it" on session "main"
    Then the exec tool is executed
    And the claude binary was invoked exactly twice
    And the second invocation included the tool result serialized in the prompt text
    And the response is "done"

  Scenario: a bare JSON call in a markdown code fence executes the tool (isaac-jkx7)
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return in sequence:
      | response                                                                          |
      | ```{"name":"exec__run","arguments":{"command":"echo fenced"}}```                  |
      | done                                                                              |
    When the user sends "run it" on session "main"
    Then the exec tool is executed
    And the claude binary was invoked exactly twice
    And the response is "done"

  Scenario: fence then invoke in one reply executes both, in order (isaac-jkx7)
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return in sequence:
      | response                                                                                                                                                    |
      | <tool_call>{"name":"exec__run","arguments":{"command":"echo one"}}</tool_call> then <invoke name="exec__run"><parameter name="command">echo two</parameter></invoke> |
      | done                                                                                                                                                        |
    When the user sends "run both" on session "main"
    Then the exec tool is executed 2 times
    And the exec tool ran commands in order:
      | command  |
      | echo one |
      | echo two |

  Scenario: text after a parsed call block is not persisted as assistant content (isaac-jkx7)
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return in sequence:
      | response                                                                                                     |
      | <tool_call>{"name":"exec__run","arguments":{"command":"git log -1"}}</tool_call>OK / b55d4964 plan: fabricated |
      | done                                                                                                         |
    When the user sends "what is the last commit" on session "main"
    Then the exec tool is executed
    And session "main" has no transcript entry containing "fabricated"

  Scenario: a malformed fence gets one corrective re-prompt and a well-formed retry executes (isaac-jkx7)
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return in sequence:
      | response                                                                          |
      | <tool_call>{"name":"exec__run","arguments":{"command":"echo "unescaped" }}</tool_call> |
      | <tool_call>{"name":"exec__run","arguments":{"command":"echo fixed"}}</tool_call>   |
      | done                                                                              |
    When the user sends "run it" on session "main"
    Then the claude binary was invoked exactly 3 times
    And the second invocation's prompt text contains "could not be parsed"
    And the second invocation's prompt text contains "<tool_call>"
    And the exec tool is executed
    And the response is "done"
    And the log has entries matching:
      | level | event                        |
      | :warn | :claude-cli/tool-syntax-drift |

  Scenario: a call-shaped block that still does not parse after the re-prompt ends the turn with a tool-protocol error, not a verdict (isaac-jkx7)
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return in sequence:
      | response                                                                          |
      | <invoke name="exec__run"><parameter name="command">echo one</invoke>              |
      | <invoke name="exec__run"><parameter name="command">echo one</invoke>              |
    When the user sends "run it" on session "main"
    Then the claude binary was invoked exactly twice
    And the turn ends with error :tool-protocol
    And session "main" has no transcript entry with role "assistant" containing "<invoke"
    And the log has entries matching:
      | level  | event                        | attempt |
      | :warn  | :claude-cli/tool-syntax-drift | 1       |
      | :error | :claude-cli/tool-protocol     | 2       |

  Scenario: a tool-protocol error is weather to hail — no delivery attempt is burned (isaac-jkx7)
    The provider contract failing is not the bean's fault; the delivery
    defers (hails-never-die) instead of counting toward dead-letter.
    Given the crew has tools: [exec]
    And the claude binary is stubbed to return in sequence:
      | response                                                             |
      | <invoke name="exec__run"><parameter name="command">echo one</invoke> |
      | <invoke name="exec__run"><parameter name="command">echo one</invoke> |
    And a hail delivery is bound to session "main"
    When the hail delivery runs its turn
    Then the delivery is deferred with attempts 0
    And the log has entries matching:
      | event          | error          |
      | :hail/deferred | :tool-protocol |
