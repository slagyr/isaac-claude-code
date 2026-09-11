Feature: Claude's per-turn tool registry over HTTP
  Claude Code receives Isaac tools through an MCP route owned by this module.
  The per-turn registry remains in the agent; this module exposes it at
  POST /claude/turns/{id} for the provider-driven loop.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the crew "main" allows tools: "exec/run,fs/read"
    And the following sessions exist:
      | name     |
      | mcp-sess |

  Scenario: tools/list renders exactly the turn's allowed tools with their schemas
    Given a turn "t-list" is registered for session "mcp-sess"
    When an MCP request is posted to "/claude/turns/t-list":
      """
      {"jsonrpc":"2.0","id":1,"method":"tools/list"}
      """
    Then the MCP response matches:
      | key                              | value     |
      | result.tools[0].name             | exec__run |
      | result.tools[0].inputSchema.type | object    |
      | result.tools[1].name             | fs__read  |
      | result.tools[1].inputSchema.type | object    |
    And the log has entries matching:
      | event             | turn   | count |
      | :mcp/tools-listed | t-list | 2     |

  Scenario: tools/call runs the drive's tool function and persists the pair on the session
    Given a turn "t-call" is registered for session "mcp-sess"
    When an MCP request is posted to "/claude/turns/t-call":
      """
      {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"echo hi"}}}
      """
    Then the MCP response matches:
      | key                    | value     |
      | result.isError         | false     |
      | result.content[0].type | text      |
      | result.content[0].text | #"(?s)hi" |
    And session "mcp-sess" has transcript matching:
      | type       | name      |
      | toolCall   | exec__run |
      | toolResult |           |

  Scenario: a failing tool returns isError, not a JSON-RPC error
    Given a turn "t-fail" is registered for session "mcp-sess"
    When an MCP request is posted to "/claude/turns/t-fail":
      """
      {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fs__read","arguments":{"file_path":"/nope/missing.txt"}}}
      """
    Then the MCP response matches:
      | key            | value |
      | result.isError | true  |
      | id             | 3     |

  Scenario: an oversized tool result is capped by isaac before it is returned
    Given a turn "t-big" is registered for session "mcp-sess"
    And config:
      | tools.defaults.max-bytes | 4096 |
    When an MCP request is posted to "/claude/turns/t-big":
      """
      {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"head -c 200000 /dev/zero | tr '\\0' x"}}}
      """
    Then the MCP response matches:
      | key                    | value             |
      | result.isError         | false             |
      | result.content[0].text | #"(?s)truncated" |

  Scenario: an unknown or ended turn is refused and nothing executes
    Given a turn "t-done" is registered for session "mcp-sess"
    And the turn "t-done" is cleared
    When an MCP request is posted to "/claude/turns/t-done":
      """
      {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"exec__run","arguments":{"command":"echo never"}}}
      """
    Then the MCP response matches:
      | key           | value                  |
      | error.code    | -32001                 |
      | error.message | #"(?i)turn not active" |
    And session "mcp-sess" has transcript not matching:
      | type     | name      |
      | toolCall | exec__run |
