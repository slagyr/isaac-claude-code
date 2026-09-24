Feature: Claude Code's HTTP MCP client talks straight to the turn's listener (isaac-mbnb)
  No stdio bridge process sits between Claude Code and the per-turn listener:
  the MCP config names the listener's own loopback URL, and Claude Code's
  Streamable-HTTP MCP client POSTs JSON-RPC lines to it directly, bearer in
  the Authorization header. The listener answers initialize itself (echoing
  the request's protocolVersion) and 202s notifications with an empty body;
  everything else (tools/list, tools/call) still goes through the per-turn
  registry unchanged.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the crew "main" allows tools: "exec/run,fs/read"
    And the following sessions exist:
      | name     |
      | mcp-sess |

  Scenario: initialize echoes the request's protocolVersion, notifications get an empty 202, and tools still work
    Given a turn "t-relay" is registered for session "mcp-sess"
    When the listener for turn "t-relay" receives an MCP request:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
      """
    Then the MCP HTTP status is 200
    And the MCP response matches:
      | key                       | value      |
      | id                        | 1          |
      | result.protocolVersion    | 2025-03-26 |
      | result.serverInfo.name    | isaac      |
      | result.capabilities.tools | {}         |
    When the listener for turn "t-relay" receives an MCP request:
      """
      {"jsonrpc":"2.0","method":"notifications/initialized"}
      """
    Then the MCP HTTP status is 202
    And the MCP response body is empty
    When the listener for turn "t-relay" receives an MCP request:
      """
      {"jsonrpc":"2.0","id":2,"method":"tools/list"}
      """
    Then the MCP HTTP status is 200
    And the MCP response matches:
      | key                  | value     |
      | id                   | 2         |
      | result.tools[0].name | exec__run |
      | result.tools[1].name | fs__read  |
    When the listener for turn "t-relay" receives an MCP request:
      """
      {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"echo hi"}}}
      """
    Then the MCP HTTP status is 200
    And the MCP response matches:
      | key                    | value     |
      | id                     | 3         |
      | result.isError         | false     |
      | result.content[0].text | #"(?s)hi" |

  Scenario: a request with the wrong bearer is refused with 401 and nothing executes
    Given a turn "t-guard" is registered for session "mcp-sess"
    When the listener for turn "t-guard" receives an MCP request with bearer "Bearer not-the-nonce":
      """
      {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"echo never"}}}
      """
    Then the MCP HTTP status is 401
    And session "mcp-sess" has transcript not matching:
      | type     | name      |
      | toolCall | exec__run |

  Scenario: a GET request to the listener is refused
    Given a turn "t-get" is registered for session "mcp-sess"
    When a GET request is made to the listener for turn "t-get"
    Then the MCP HTTP status is 405

  Scenario: the listener is stopped at turn end and refuses further requests
    Given a turn "t-done" is registered for session "mcp-sess"
    And the listener for turn "t-done" is stopped
    When the listener for turn "t-done" receives an MCP request:
      """
      {"jsonrpc":"2.0","id":5,"method":"tools/list"}
      """
    Then the MCP request fails to connect

  Scenario: the module contributes no CLI command — the transport is internal plumbing, never an operator-facing subcommand (isaac-1q9m)
    Then the claude-code manifest declares no :isaac/cli commands
