(require '[isaac.provider.claude-code.api.claude-cli :as cli])
(let [a (#'cli/write-mcp-config! "http://127.0.0.1:1" "test-nonce-a")
      b (#'cli/write-mcp-config! "http://127.0.0.1:2" "test-nonce-b")]
  (println a)
  (println b)
  (flush)
  @(promise))
