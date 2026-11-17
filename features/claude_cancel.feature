Feature: Stop kills only the Claude Code process for its turn

  Scenario: every cancel in one server process kills its turn's claude
    Given a Claude Code command that runs for 8 seconds unless killed
    When a turn on session "s1" starts and is cancelled after 0.4 seconds
    And a turn on session "s2" starts and is cancelled after 0.4 seconds
    And a turn on session "s3" starts and is cancelled after 0.4 seconds
    Then each of the three turns ends within 3 seconds of its cancel
    And the three cancels log three driver terminations

  Scenario: a cancel reaches only its own session's claude
    Given a Claude Code command that runs for 8 seconds unless killed
    When turns on sessions "a" and "b" are running
    And the turn on session "a" is cancelled
    Then the turn on session "a" ends within 3 seconds
    And the turn on session "b" is still running 0.5 seconds later
