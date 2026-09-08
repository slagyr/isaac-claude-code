(ns isaac.llm.claude-driver-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
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
        (should (<= 0 (.indexOf second-argv "--print")))))))
