Feature: Claude subscription quota from the CLI's own rate-limit events
  A claude-code provider meters its quota for provider__quota
  (isaac-agent's :isaac.agent/quota-meter berth). The source is the
  rate_limit_event the claude CLI emits on every turn: it carries each
  window's utilization and reset time for the whole account, whatever
  else spends it. The meter keeps the latest event per provider id, so a
  report is as fresh as that provider's last turn — checked_at says when.

  Why not Anthropic's usage endpoint: it requires the user:profile scope,
  and the long-lived setup tokens these providers authenticate with
  (CLAUDE_CODE_OAUTH_TOKEN) are refused with 403 oauth_scope_insufficient
  (checked 2026-10-28).

  unifiedWindows utilization is a 0–1 fraction; the report carries
  percent (fraction × 100). A provider that has not run a turn since the
  server started reports :unknown.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/providers/claude-code.edn" exists with:
      | path    | value  |
      | command | claude |
    And the isaac EDN file "config/models/sub-sonnet.edn" exists with:
      | path     | value       |
      | model    | sonnet      |
      | provider | claude-code |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | sub-sonnet  |
      | soul  | Think hard. |
    And the following sessions exist:
      | name | crew    |
      | main | thinker |

  Scenario: After a turn, the quota reports the windows the CLI announced
    Given a fake Claude Code on the path scripted with:
      | cycle | kind       | payload |
      | 1     | rate_limit | {"status":"allowed","resetsAt":1793192400,"rateLimitType":"five_hour","overageStatus":"rejected","isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.31,"resetsAt":1793192400},"seven_day":{"utilization":0.12,"resetsAt":1793610000}}} |
      | 1     | text       | done    |
    When the user sends "hi" on session "main"
    Then the response is "done"
    When the tool "provider__quota" is called with:
      | provider | claude-code |
    Then the tool result is not an error
    And the tool result JSON has:
      | path                                   | value                |
      | providers[0].provider                  | claude-code          |
      | providers[0].status                    | ok                   |
      | providers[0].checked_at                | #*                   |
      | providers[0].windows[0].id             | five_hour            |
      | providers[0].windows[0].label          | 5-hour               |
      | providers[0].windows[0].used_percent   | 31.0                 |
      | providers[0].windows[0].resets_at      | 2026-10-28T13:00:00Z |
      | providers[0].windows[0].limited        | false                |
      | providers[0].windows[1].id             | seven_day            |
      | providers[0].windows[1].label          | 7-day                |
      | providers[0].windows[1].used_percent   | 12.0                 |
      | providers[0].windows[1].resets_at      | 2026-11-02T09:00:00Z |

  Scenario: A rejected window is marked limited
    Given a fake Claude Code on the path scripted with:
      | cycle | kind       | payload |
      | 1     | rate_limit | {"status":"rejected","resetsAt":1793610000,"rateLimitType":"seven_day","unifiedWindows":{"five_hour":{"utilization":0.4,"resetsAt":1793192400},"seven_day":{"utilization":1.0,"resetsAt":1793610000}}} |
      | 1     | text       | done    |
    When the user sends "hi" on session "main"
    And the tool "provider__quota" is called with:
      | provider | claude-code |
    Then the tool result JSON has:
      | path                                 | value |
      | providers[0].windows[0].id           | five_hour |
      | providers[0].windows[0].limited      | false |
      | providers[0].windows[1].id           | seven_day |
      | providers[0].windows[1].used_percent | 100.0 |
      | providers[0].windows[1].limited      | true  |

  Scenario: Before its first turn a provider's quota is unknown
    When the tool "provider__quota" is called with:
      | provider | claude-code |
    Then the tool result is not an error
    And the tool result JSON has:
      | path                  | value             |
      | providers[0].provider | claude-code       |
      | providers[0].status   | unknown           |
      | providers[0].error    | #"(?i).*no turn.*" |
      | providers[0].windows  | []                |
