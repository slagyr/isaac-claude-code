Feature: The mcp bridge relays Claude Code's MCP lines to the turn's listener (isaac-ejj3)
  Claude Code spawns the bridge as a stdio MCP server under Babashka. It answers
  initialize locally, drops notifications, and authenticates relayed lines with
  the per-turn nonce inherited through ISAAC_MCP_NONCE.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the crew "main" allows tools: "exec/run,fs/read"
    And the following sessions exist:
      | name     |
      | mcp-sess |

  Scenario: the bridge answers initialize itself and relays tools/list to the turn's listener
    Given a turn "t-relay" is registered for session "mcp-sess"
    When the mcp bridge relays for turn "t-relay":
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
      {"jsonrpc":"2.0","method":"notifications/initialized"}
      {"jsonrpc":"2.0","id":2,"method":"tools/list"}
      """
    Then the MCP response matches:
      | key                  | value     |
      | id                   | 2         |
      | result.tools[0].name | exec__run |
      | result.tools[1].name | fs__read  |

  Scenario: a bridge whose nonce the listener refuses answers with a JSON-RPC error and nothing executes
    Given a turn "t-guard" is registered for session "mcp-sess"
    And environment variable "ISAAC_MCP_NONCE" is "not-the-nonce"
    When the mcp bridge relays for turn "t-guard":
      """
      {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"echo never"}}}
      """
    Then the MCP response matches:
      | key           | value               |
      | id            | 3                   |
      | error.code    | -32001              |
      | error.message | #"(?i)unauthorized" |
    And session "mcp-sess" has transcript not matching:
      | type     | name      |
      | toolCall | exec__run |
