(ns isaac.llm.claude-driver-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.config.loader :as config-loader]
    [isaac.llm.api.claude-cli :as sut]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(defn- ndjson [events]
  (str/join "\n" (map json/generate-string events)))

(defn- usage [input cache-read cache-write]
  {:input_tokens                input
   :output_tokens               4
   :cache_read_input_tokens     cache-read
   :cache_creation_input_tokens cache-write})

(defn- result-line [text u]
  {:type "result" :result text :usage u})

(describe "claude-cli LoopDriver"

  (before
    (sut/clear-stub!)
    (sut/clear-invocations!)
    (sut/clear-fake-cli!)
    (tool-loop/clear-provider-driver!))

  (after
    (sut/clear-stub!)
    (sut/clear-invocations!)
    (sut/clear-fake-cli!)
    (tool-loop/clear-provider-driver!))

  (it "declares drives-tool-loop? and uses stream-json input when configured"
    (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
      (should (:drives-tool-loop? (api/config api)))
      (sut/set-stub!
        (constantly {:exit 0
                     :out  (ndjson [{:type "assistant"
                                     :message {:content [{:type "text" :text "ok"}]}}
                                    (result-line "ok" (usage 10 0 0))])
                     :err  ""}))
      (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
      (let [argv (:argv (first (sut/invocations)))]
        (should (some #(= "--input-format" %) argv))
        (should= "stream-json" (nth argv (inc (.indexOf argv "--input-format")))))))

  (it "does not install stream-json input when drives-tool-loop? is omitted"
    (let [api (sut/make "claude" {:command "claude"})]
      (should-not (:drives-tool-loop? (api/config api)))
      (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
      (let [argv (:argv (first (sut/invocations)))]
        (should (neg? (.indexOf argv "--input-format"))))))

  (it "drives one tool_use cycle then a text result through the drive tool-fn"
    (let [tool-runs (atom [])
          on-cycle  (atom [])
          calls*    (atom 0)
          events    [(ndjson [{:type "assistant"
                               :message {:content [{:type "tool_use"
                                                    :id   "call_1"
                                                    :name "exec__run"
                                                    :input {:command "echo hi"}}]}}
                              (result-line "" (usage 200 50 10))])
                     (ndjson [{:type "assistant"
                               :message {:content [{:type "text" :text "hi came back"}]}}
                              (result-line "hi came back" (usage 260 60 0))])]]
      (sut/set-stub!
        (fn [_]
          (let [n (swap! calls* inc)]
            {:exit 0 :out (nth events (dec n) (last events)) :err ""})))
      (let [api    (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            result (tool-loop/run
                     (fn [req] (api/chat api req))
                     (fn [req _resp _tcs _trs] (:messages req))
                     {:model "sonnet" :messages [{:role "user" :content "run it"}]}
                     (fn [name args]
                       (swap! tool-runs conj [name args])
                       "hi\n")
                     {:api      api
                      :on-cycle (fn [phase n _] (swap! on-cycle conj [phase n]))})]
        (should= [["exec__run" {:command "echo hi"}]] @tool-runs)
        (should= "hi came back" (get-in result [:response :message :content]))
        (let [u (get-in result [:response :usage])]
          (should= 320 (+ (:input-tokens u) (:cache-read u) (:cache-write u))))
        (should= 580 (:input-tokens (:token-counts result)))
        (should= [[:start 1] [:end 1] [:start 2] [:end 2]] @on-cycle))))

  (it "still folds cache into turn token-counts when the global driver was cleared after make"
    (let [calls* (atom 0)
          events [(ndjson [{:type    "assistant"
                            :message {:content [{:type  "tool_use"
                                                 :id    "call_1"
                                                 :name  "exec__run"
                                                 :input {:command "echo hi"}}]}}
                           (result-line "" (usage 200 50 10))])
                  (ndjson [{:type    "assistant"
                            :message {:content [{:type "text" :text "hi came back"}]}}
                           (result-line "hi came back" (usage 260 60 0))])]]
      (sut/set-stub!
        (fn [_]
          (let [n (swap! calls* inc)]
            {:exit 0 :out (nth events (dec n) (last events)) :err ""})))
      (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
        (tool-loop/clear-provider-driver!)
        (let [result (tool-loop/run
                       (fn [req] (api/chat api req))
                       (fn [req _resp _tcs _trs] (:messages req))
                       {:model "sonnet" :messages [{:role "user" :content "run it"}]}
                       (fn [_name _args] "hi\n")
                       {:api api})]
          (should= 580 (:input-tokens (:token-counts result)))))))

  (it "does not treat a fake CLI as LoopDriver without drives-tool-loop?"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "ok"}])
    (let [api (sut/make "claude" {:command "claude"})]
      (should-not (:drives-tool-loop? (api/config api)))))

  (it "survives augment-provider remake that drops drives-tool-loop?"
    (let [calls* (atom 0)
          events [(ndjson [{:type    "assistant"
                            :message {:content [{:type  "tool_use"
                                                 :id    "call_1"
                                                 :name  "exec__run"
                                                 :input {:command "echo hi"}}]}}
                           (result-line "" (usage 200 50 10))])
                  (ndjson [{:type    "assistant"
                            :message {:content [{:type "text" :text "hi came back"}]}}
                           (result-line "hi came back" (usage 260 60 0))])]]
      (sut/set-stub!
        (fn [_]
          (let [n (swap! calls* inc)]
            {:exit 0 :out (nth events (dec n) (last events)) :err ""})))
      (sut/make "claude" {:command "claude" :api "claude-cli" :drives-tool-loop? true})
      (let [api    (sut/make "claude" {:session-key "main" :root "/tmp" :context-window 32768})
            result (tool-loop/run
                     (fn [req] (api/chat api req))
                     (fn [req _resp _tcs _trs] (:messages req))
                     {:model "sonnet" :messages [{:role "user" :content "run it"}]}
                     (fn [_name _args] "hi\n")
                     {:api api})]
        (should (:drives-tool-loop? (api/config api)))
        (should= 580 (:input-tokens (:token-counts result))))))

  (it "survives augment-provider remake that drops extra-args and command"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type "result" :result "ok"})
                   :err  ""}))
    (sut/make "claude" {:command     "/custom/path/claude"
                       :api         "claude-cli"
                       :extra-args  ["--foo" "bar"]
                       :drives-tool-loop? false})
    (let [api (sut/make "claude" {:session-key "main" :root "/tmp" :context-window 32768})]
      (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
      (let [argv (:argv (first (sut/invocations)))]
        (should-not (:drives-tool-loop? (api/config api)))
        (should= "/custom/path/claude" (first argv))
        (should= "bar" (nth argv (inc (.indexOf argv "--foo")))))))

  (it "survives augment-provider remake that drops stream-non-tool-turns"
    (sut/make "claude" {:command                "claude"
                       :api                    "claude-cli"
                       :stream-non-tool-turns  true
                       :drives-tool-loop?      false})
    (let [api (sut/make "claude" {:session-key "main" :root "/tmp" :context-window 32768})]
      (should (:stream-non-tool-turns (api/config api)))
      (should-not (:drives-tool-loop? (api/config api)))))

  (it "survives augment-provider remake that drops stream-supports-tool-calls false"
    (sut/make "claude" {:command                     "claude"
                       :api                         "claude-cli"
                       :stream-supports-tool-calls  false
                       :drives-tool-loop?           false})
    (let [api (sut/make "claude" {:session-key "main" :root "/tmp" :context-window 32768})]
      (should= false (:stream-supports-tool-calls (api/config api)))
      (should-not (:drives-tool-loop? (api/config api)))))

  (it "emits thinking as :reasoning on stream chunks"
    (let [chunks (atom [])
          out    (ndjson [{:type  "content_block_delta"
                           :delta {:type "thinking_delta" :thinking "weighing the options"}}
                          {:type  "content_block_delta"
                           :delta {:text "here is my answer"}}
                          (result-line "here is my answer" (usage 10 0 0))])]
      (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
      (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "think"}]}
                       (fn [chunk] (swap! chunks conj chunk))
                       "claude"
                       {:command "claude"})
      (should (some #(= "weighing the options" (:reasoning %)) @chunks))
      (should (some #(= "here is my answer" (get-in % [:message :content])) @chunks))))

  (it "logs that no title-generation CLI switch was found on a driven turn"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (ndjson [{:type "assistant"
                                   :message {:content [{:type "text" :text "ok"}]}}
                                  (result-line "ok" (usage 10 0 0))])
                   :err  ""}))
    (log/capture-logs
      (let [api   (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            _     (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
            entry (first (filter #(= :claude/title-side-call (:event %)) @log/captured-logs))]
        (should-not-be-nil entry)
        (should= false (:found entry)))))

  (it "falls back to the fence path and logs when MCP init fails"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type "result" :result "fenced"})
                   :err  ""}))
    (log/capture-logs
      (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            _   (sut/fail-mcp-init!)
            res (api/chat api {:model "sonnet" :messages [{:role "user" :content "fallback"}]})
            entry (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))]
        (should= "fenced" (get-in res [:message :content]))
        (should-not-be-nil entry)
        (should= "claude" (:provider entry))
        (should= :mcp-init (:reason entry))
        (let [argv (:argv (first (sut/invocations)))]
          (should (<= 0 (.indexOf argv "--print")))
          (should= "json" (nth argv (inc (.indexOf argv "--output-format"))))))))

  (it "falls back to fence json even when the caller requested a stream"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type "result" :result "fenced"})
                   :err  ""}))
    (sut/fail-mcp-init!)
    (let [chunks (atom [])
          res    (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "fallback"}]}
                                  (fn [chunk] (swap! chunks conj chunk))
                                  "claude"
                                  {:command "claude" :drives-tool-loop? true})
          argv   (:argv (first (sut/invocations)))]
      (should= "fenced" (get-in res [:message :content]))
      (should= "json" (nth argv (inc (.indexOf argv "--output-format"))))
      (should (neg? (.indexOf argv "--input-format")))))

  (it "passes --verbose with stream-json on a driven turn"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (ndjson [{:type    "assistant"
                                   :message {:content [{:type "text" :text "ok"}]}}
                                  (result-line "ok" (usage 10 0 0))])
                   :err  ""}))
    (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
      (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
      (let [argv (:argv (first (sut/invocations)))]
        (should= "stream-json" (nth argv (inc (.indexOf argv "--output-format"))))
        (should (<= 0 (.indexOf argv "--verbose"))))))

  (it "passes --verbose with stream-json on a streaming turn"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (ndjson [{:type "content_block_delta" :delta {:text "ok"}}
                                  (result-line "ok" (usage 10 0 0))])
                   :err  ""}))
    (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                     (fn [_])
                     "claude"
                     {:command "claude"})
    (let [argv (:argv (first (sut/invocations)))]
      (should= "stream-json" (nth argv (inc (.indexOf argv "--output-format"))))
      (should (<= 0 (.indexOf argv "--verbose")))))

  (it "fake CLI rejects stream-json without --verbose the way the real CLI does"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "ok"}])
    (let [result (#'sut/simulate-fake-cli! ["claude" "--print" "--output-format" "stream-json"] "hi")]
      (should= 1 (:exit result))
      (should (str/includes? (:err result) "When using --print, --output-format=stream-json requires --verbose"))
      (should= "" (:out result))))

  (it "falls back to the fence path when the CLI exits before the first stream event"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "fenced"}])
    (sut/exit-before-streaming! 1 "Error: When using --print, --output-format=stream-json requires --verbose")
    (log/capture-logs
      (let [api    (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            res    (api/chat api {:model "sonnet" :messages [{:role "user" :content "fallback"}]})
            entry  (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))
            first-argv  (:argv (first (sut/invocations)))
            second-argv (:argv (second (sut/invocations)))]
        (should= "fenced" (get-in res [:message :content]))
        (should-not-be-nil entry)
        (should= "claude" (:provider entry))
        (should= :cli-start-failed (:reason entry))
        (should (re-find #"stream-json requires --verbose" (str (:stderr entry))))
        (should= 2 (count (sut/invocations)))
        (should= "stream-json" (nth first-argv (inc (.indexOf first-argv "--output-format"))))
        (should (<= 0 (.indexOf first-argv "--verbose")))
        (should= "json" (nth second-argv (inc (.indexOf second-argv "--output-format"))))
        (should (<= 0 (.indexOf second-argv "--print"))))))

  (it "assembles the reply from stream_event content_block_delta text_delta chunks and logs driver-exit"
    (let [out (ndjson [{:type  "stream_event"
                        :event {:type  "content_block_delta"
                                :delta {:type "text_delta" :text "po"}}}
                       {:type  "stream_event"
                        :event {:type  "content_block_delta"
                                :delta {:type "text_delta" :text "ng"}}}
                       {:type    "message"
                        :message {:role    "assistant"
                                  :content [{:type "text" :text "pong"}]}}
                       {:type        "result"
                        :is_error    false
                        :stop_reason "end_turn"
                        :usage       (usage 2 3289 5473)}])]
      (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
      (log/capture-logs
        (let [api      (sut/make "claude" {:command "claude" :drives-tool-loop? true})
              res      (api/chat api {:model "sonnet" :messages [{:role "user" :content "Reply with exactly: pong"}]})
              exit-log (first (filter #(= :claude/driver-exit (:event %)) @log/captured-logs))]
          (should= "pong" (get-in res [:message :content]))
          (should= 2 (:input-tokens (:usage res)))
          (should= 3289 (:cache-read (:usage res)))
          (should= 5473 (:cache-write (:usage res)))
          (should-not-be-nil exit-log)
          (should= "claude" (:provider exit-log))
          (should= 0 (:exit-code exit-log))
          (should= true (:result-event exit-log))))))

  (it "fake CLI emits stream_event text_delta shapes for kind text_delta"
    (sut/set-fake-cli! [{:cycle 1 :kind "text_delta" :payload "po"}
                        {:cycle 1 :kind "text_delta" :payload "ng"}
                        {:cycle 1 :kind "usage" :payload "{\"input_tokens\":2,\"cache_read_input_tokens\":3289,\"cache_creation_input_tokens\":5473}"}])
    (let [envelope (json/generate-string {:type "user" :message {:role "user" :content "hi"}})
          result (#'sut/simulate-fake-cli! ["claude" "--print" "--output-format" "stream-json" "--verbose"] envelope)
          events (map #(json/parse-string % true) (str/split-lines (:out result)))]
      (should= 0 (:exit result))
      (should (some #(= "stream_event" (:type %)) events))
      (should (some #(= "text_delta" (get-in % [:event :delta :type])) events))
      (should (some #(= "message" (:type %)) events))
      (should (some #(= "result" (:type %)) events))
      (should= false (:is_error (last (filter #(= "result" (:type %)) events))))))

  (it "fake CLI emits an is_error result for kind error_result"
    (sut/set-fake-cli! [{:cycle 1 :kind "error_result" :payload "MCP server \"isaac\" failed to connect"}])
    (let [envelope (json/generate-string {:type "user" :message {:role "user" :content "hi"}})
          result (#'sut/simulate-fake-cli! ["claude" "--print" "--output-format" "stream-json" "--verbose"] envelope)
          events (map #(json/parse-string % true) (str/split-lines (:out result)))
          result-evt (last (filter #(= "result" (:type %)) events))]
      (should= true (:is_error result-evt))
      (should (str/includes? (str (:result result-evt)) "failed to connect"))))

  (it "falls back to the fence path with :cli-error when the result event has is_error"
    (sut/set-fake-cli! [{:cycle 1 :kind "error_result" :payload "MCP server \"isaac\" failed to connect"}])
    (log/capture-logs
      (let [api         (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            _           (api/chat api {:model "sonnet" :messages [{:role "user" :content "fallback please"}]})
            exit-log    (first (filter #(= :claude/driver-exit (:event %)) @log/captured-logs))
            fallback    (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))
            first-argv  (:argv (first (sut/invocations)))
            second-argv (:argv (second (sut/invocations)))]
        (should-not-be-nil exit-log)
        (should-not-be-nil fallback)
        (should= "claude" (:provider fallback))
        (should= :cli-error (:reason fallback))
        (should (re-find #"(?s).*failed to connect.*" (str (:stderr fallback))))
        (should= 2 (count (sut/invocations)))
        (should= "stream-json" (nth first-argv (inc (.indexOf first-argv "--output-format"))))
        (should= "json" (nth second-argv (inc (.indexOf second-argv "--output-format"))))
        (should (<= 0 (.indexOf second-argv "--print"))))))

  (it "falls back to the fence path with :cli-error when the process exits with no result event"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (ndjson [{:type  "stream_event"
                                   :event {:type  "content_block_delta"
                                           :delta {:type "text_delta" :text ""}}}])
                   :err  "MCP server \"isaac\" failed to connect"}))
    (log/capture-logs
      (let [api      (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            _        (api/chat api {:model "sonnet" :messages [{:role "user" :content "fallback please"}]})
            fallback (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))]
        (should-not-be-nil fallback)
        (should= :cli-error (:reason fallback))
        (should (re-find #"(?s).*failed to connect.*" (str (:stderr fallback)))))))

  (it "emits stream-json user envelopes on stdin, never bare role/content lines"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "second"}])
    (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
      (api/chat api {:model    "sonnet"
                     :messages [{:role "user" :content "one"}
                                {:role "assistant" :content "first"}
                                {:role "user" :content "two"}]})
      (let [in     (:in (first (sut/invocations)))
            lines  (str/split-lines in)
            parsed (map #(json/parse-string % true) lines)]
        (should= 2 (count parsed))
        (doseq [evt parsed]
          (should= "user" (:type evt))
          (should= "user" (get-in evt [:message :role]))
          (should (string? (get-in evt [:message :content]))))
        (should (re-find #"(?s).*one.*first.*" (get-in (first parsed) [:message :content])))
        (should= "two" (get-in (last parsed) [:message :content]))
        (should-not (some #(and (contains? % :role) (not (contains? % :type))) parsed)))))

  (it "fake CLI emits nothing when stdin has a bare role/content line"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "ok"}])
    (let [bare   (json/generate-string {:role "user" :content "hi"})
          result (#'sut/simulate-fake-cli!
                   ["claude" "--print" "--output-format" "stream-json" "--verbose"]
                   bare)]
      (should= 0 (:exit result))
      (should= "" (:out result))))

  (it "fake CLI refuses tool_use rows when driven without --mcp-config"
    (sut/set-fake-cli! [{:cycle 1 :kind "tool_use" :payload "{\"name\":\"exec__run\",\"input\":{\"command\":\"echo hi\"}}"}
                        {:cycle 1 :kind "text" :payload "should not emit"}])
    (let [envelope (json/generate-string {:type "user" :message {:role "user" :content "run it"}})
          result   (#'sut/simulate-fake-cli!
                     ["claude" "--print" "--output-format" "stream-json" "--verbose" "--input-format" "stream-json"]
                     envelope)
          events   (if (str/blank? (:out result))
                     []
                     (map #(json/parse-string % true) (str/split-lines (:out result))))]
      (should-not (some (fn [evt]
                          (some #(= "tool_use" (:type %))
                                (or (get-in evt [:message :content]) [])))
                        events))))

  (it "passes --mcp-config pointing at a temp json that runs isaac mcp-bridge for a turn"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "ok"}])
    (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
      (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
      (let [argv    (:argv (first (sut/invocations)))
            idx     (.indexOf argv "--mcp-config")
            path    (when (<= 0 idx) (nth argv (inc idx)))
            saved   (sut/last-mcp-config)
            server  (get-in saved [:body :mcpServers :isaac])
            argv*   (str/join " " (concat [(:command server)] (:args server)))]
        (should (<= 0 idx))
        (should (re-find #"\.json$" (str path)))
        (should= "isaac" (:command server))
        (should (re-find #"(?s).*mcp-bridge.*--turn.*[0-9a-f-]+.*" argv*)))))

  (it "omits the tool protocol contract from --system-prompt on a driven turn"
    (sut/set-fake-cli! [{:cycle 1 :kind "tool_use" :payload "{\"name\":\"exec__run\",\"input\":{\"command\":\"echo hi\"}}"}
                        {:cycle 1 :kind "text" :payload "hi came back"}])
    (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
      (api/chat api {:model    "sonnet"
                     :messages [{:role "user" :content "run it"}]
                     :tools    [{:type "function" :function {:name "exec__run"}}]})
      (let [argv   (:argv (first (sut/invocations)))
            idx    (.indexOf argv "--system-prompt")
            system (when (<= 0 idx) (nth argv (inc idx)))]
        (should-not (str/includes? (str system) sut/tool-protocol-contract))
        (should-not (re-find #"<tool_call>" (str system)))
        (should-not (re-find #"tool_call>" (str system))))))

  (it "fake CLI emits a system init event for kind mcp_status"
    (sut/set-fake-cli! [{:cycle 1 :kind "mcp_status" :payload "{\"mcp_servers\":[{\"name\":\"isaac\",\"status\":\"failed\"}],\"tools\":[]}"}
                        {:cycle 1 :kind "text" :payload "I have no tools"}])
    (let [envelope (json/generate-string {:type "user" :message {:role "user" :content "hi"}})
          result   (#'sut/simulate-fake-cli!
                     ["claude" "--print" "--output-format" "stream-json" "--verbose" "--mcp-config" "/tmp/x.json"]
                     envelope)
          events   (map #(json/parse-string % true) (str/split-lines (:out result)))
          init     (first (filter #(= "init" (:subtype %)) events))]
      (should= "system" (:type init))
      (should= "failed" (:status (first (:mcp_servers init))))
      (should= [] (:tools init))))

  (it "falls back and logs mcp-status when the init event reports isaac MCP failed"
    (sut/set-fake-cli! [{:cycle 1 :kind "mcp_status" :payload "{\"mcp_servers\":[{\"name\":\"isaac\",\"status\":\"failed\"}],\"tools\":[]}"}
                        {:cycle 1 :kind "text" :payload "I have no tools"}])
    (log/capture-logs
      (let [api         (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            _           (api/chat api {:model    "sonnet"
                                       :messages [{:role "user" :content "run it"}]
                                       :tools    [{:type "function" :function {:name "exec__run"}}]})
            status      (first (filter #(= :claude/mcp-status (:event %)) @log/captured-logs))
            fallback    (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))
            second-argv (:argv (second (sut/invocations)))]
        (should-not-be-nil status)
        (should= "claude" (:provider status))
        (should= 0 (:tools status))
        (should (re-find #"(?s).*isaac.*failed.*" (str (:servers status))))
        (should-not-be-nil fallback)
        (should= "claude" (:provider fallback))
        (should= :mcp-failed (:reason fallback))
        (should= 2 (count (sut/invocations)))
        (should= "json" (nth second-argv (inc (.indexOf second-argv "--output-format"))))
        (should (<= 0 (.indexOf second-argv "--print"))))))

  (it "logs mcp-status and stays on the driven path when isaac is connected with tools"
    (sut/set-fake-cli! [{:cycle 1 :kind "mcp_status" :payload "{\"mcp_servers\":[{\"name\":\"isaac\",\"status\":\"connected\"}],\"tools\":[\"exec__run\"]}"}
                        {:cycle 1 :kind "text" :payload "ok"}])
    (log/capture-logs
      (let [api      (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            res      (api/chat api {:model    "sonnet"
                                    :messages [{:role "user" :content "run it"}]
                                    :tools    [{:type "function" :function {:name "exec__run"}}]})
            status   (first (filter #(= :claude/mcp-status (:event %)) @log/captured-logs))
            fallback (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))]
        (should= "ok" (get-in res [:message :content]))
        (should-not-be-nil status)
        (should= 1 (:tools status))
        (should (re-find #"(?s).*isaac.*connected.*" (str (:servers status))))
        (should-be-nil fallback)
        (should= 1 (count (sut/invocations))))))

  (it "falls back with :mcp-failed when init reports zero tools on a turn that has tools"
    (sut/set-fake-cli! [{:cycle 1 :kind "mcp_status" :payload "{\"mcp_servers\":[{\"name\":\"isaac\",\"status\":\"connected\"}],\"tools\":[]}"}
                        {:cycle 1 :kind "text" :payload "toolless"}])
    (log/capture-logs
      (let [api      (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            _        (api/chat api {:model    "sonnet"
                                    :messages [{:role "user" :content "run it"}]
                                    :tools    [{:type "function" :function {:name "exec__run"}}]})
            fallback (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))]
        (should-not-be-nil fallback)
        (should= :mcp-failed (:reason fallback))
        (should= 2 (count (sut/invocations))))))

  (it "falls back with :mcp-failed when a driven reply still contains a tool_call fence"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "<tool_call>{\"name\":\"exec__run\",\"arguments\":{\"command\":\"echo hi\"}}</tool_call>"}])
    (log/capture-logs
      (let [api         (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            _           (api/chat api {:model    "sonnet"
                                       :messages [{:role "user" :content "run it"}]
                                       :tools    [{:type "function" :function {:name "exec__run"}}]})
            fallback    (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))
            first-argv  (:argv (first (sut/invocations)))
            second-argv (:argv (second (sut/invocations)))]
        (should-not-be-nil fallback)
        (should= :mcp-failed (:reason fallback))
        (should= 2 (count (sut/invocations)))
        (should= "stream-json" (nth first-argv (inc (.indexOf first-argv "--output-format"))))
        (should= "json" (nth second-argv (inc (.indexOf second-argv "--output-format")))))))

  (it "writes --server from the running server's port and --token from its auth token"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "ok"}])
    (with-redefs [config-loader/snapshot (constantly {:server {:port 7912 :auth {:token "harbor-secret"}}})]
      (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
        (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
        (let [saved  (sut/last-mcp-config)
              server (get-in saved [:body :mcpServers :isaac])
              argv*  (str/join " " (concat [(:command server)] (:args server)))]
          (should (re-find #"(?s).*mcp-bridge.*--turn.*--server http://127\.0\.0\.1:7912.*--token harbor-secret.*" argv*))))))

  (it "provider :mcp-server-url and :mcp-token override the running server"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "ok"}])
    (with-redefs [config-loader/snapshot (constantly {:server {:port 7912 :auth {:token "harbor-secret"}}})]
      (let [api (sut/make "claude" {:command        "claude"
                                    :drives-tool-loop? true
                                    :mcp-server-url "http://127.0.0.1:9000"
                                    :mcp-token      "override-token"})]
        (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
        (let [saved  (sut/last-mcp-config)
              server (get-in saved [:body :mcpServers :isaac])
              argv*  (str/join " " (concat [(:command server)] (:args server)))]
          (should (re-find #"--server http://127\.0\.0\.1:9000" argv*))
          (should (re-find #"--token override-token" argv*))
          (should-not (re-find #"harbor-secret" argv*))))))

  (it "defaults --server to 127.0.0.1:6674 when no server config is set"
    (sut/set-fake-cli! [{:cycle 1 :kind "text" :payload "ok"}])
    (with-redefs [config-loader/snapshot (constantly nil)]
      (let [api (sut/make "claude" {:command "claude" :drives-tool-loop? true})]
        (api/chat api {:model "sonnet" :messages [{:role "user" :content "hi"}]})
        (let [saved  (sut/last-mcp-config)
              server (get-in saved [:body :mcpServers :isaac])
              argv*  (str/join " " (concat [(:command server)] (:args server)))]
          (should (re-find #"--server http://127\.0\.0\.1:6674" argv*))
          (should-not (re-find #"--token" argv*))))))

  (it "does not fall back when init reports isaac pending with zero tools"
    (sut/set-fake-cli! [{:cycle 1 :kind "mcp_status" :payload "{\"mcp_servers\":[{\"name\":\"isaac\",\"status\":\"pending\"}],\"tools\":[]}"}
                        {:cycle 1 :kind "tool_use" :payload "{\"name\":\"exec__run\",\"input\":{\"command\":\"echo hi\"}}"}
                        {:cycle 1 :kind "text" :payload "hi came back"}])
    (log/capture-logs
      (let [api      (sut/make "claude" {:command "claude" :drives-tool-loop? true})
            res      (api/chat api {:model    "sonnet"
                                    :messages [{:role "user" :content "run it"}]
                                    :tools    [{:type "function" :function {:name "exec__run"}}]})
            status   (first (filter #(= :claude/mcp-status (:event %)) @log/captured-logs))
            fallback (first (filter #(= :claude/driver-fallback (:event %)) @log/captured-logs))]
        (should= "hi came back" (get-in res [:message :content]))
        (should-not-be-nil status)
        (should (re-find #"(?s).*isaac.*pending.*" (str (:servers status))))
        (should-be-nil fallback)
        (should= 1 (count (sut/invocations)))
        (should (seq (get-in res [:message :tool_calls])))))))
