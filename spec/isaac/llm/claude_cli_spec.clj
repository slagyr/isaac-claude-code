(ns isaac.llm.claude-cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.drive.turn :as drive-turn]
    [isaac.fs :as fs]
    [isaac.llm.api.claude-cli :as sut]
    [isaac.llm.api.protocol :as api]
    [isaac.marigold :as marigold]
    [isaac.marigold.agent :as marigold.agent]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as session-helper]
    [speclj.core :refer [around before describe it should should= should-not]]))

(def ^:private transcript-test-dir marigold/home)

(describe "claude-cli api"
  (before
    (sut/clear-stub!)
    (sut/clear-invocations!)
    (sut/set-stub! (constantly {:exit 0
                                :out  (json/generate-string {:type   "result"
                                                             :result "hi"
                                                             :usage  {:input_tokens 1 :output_tokens 1}})
                                :err  ""})))

  (it "returns assistant content from stubbed binary output"
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude"
                        {:command "claude"})]
      (should= "hi" (:content res))))

  (it "invokes via Api protocol"
    (let [p   (sut/make "claude" {:command "claude"})
          res (api/chat p {:model "sonnet" :messages [{:role "user" :content "yo"}]})]
      (should= "hi" (:content res))))

  (it "streams NDJSON deltas"
    (let [out (str/join "\n"
                        (map #(json/generate-string
                                {:type "content_block_delta" :delta {:text %}})
                             ["Hello" " " "world"]))
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          chunks (atom [])]
      (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                       (fn [chunk]
                         (when-let [piece (:text-delta chunk)]
                           (swap! chunks conj piece)))
                       "claude"
                       {:command "claude" :stream-non-tool-turns true})
      (should= ["Hello" " " "world"] @chunks)))

  (it "omits the reasoning block when every streamed thinking delta is blank (isaac-ddls)"
    (let [out (str/join "\n"
                        (concat
                          (map #(json/generate-string
                                  {:type "content_block_delta" :delta {:type "thinking_delta" :thinking %}})
                               ["" "\n" "   "])
                          [(json/generate-string
                             {:type "content_block_delta" :delta {:type "text_delta" :text "answered"}})]))
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          res (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                               (fn [_chunk])
                               "claude"
                               {:command "claude" :stream-non-tool-turns true})]
      (should= "answered" (:content res))
      (should= nil (:reasoning res))))

  (it "keeps the reasoning block when a streamed thinking delta carries text (isaac-ddls)"
    (let [out (str/join "\n"
                        (concat
                          (map #(json/generate-string
                                  {:type "content_block_delta" :delta {:type "thinking_delta" :thinking %}})
                               ["weigh" "ing it"])
                          [(json/generate-string
                             {:type "content_block_delta" :delta {:type "text_delta" :text "answered"}})]))
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          res (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                               (fn [_chunk])
                               "claude"
                               {:command "claude" :stream-non-tool-turns true})]
      (should= {:summary "weighing it"} (:reasoning res))))

  (it "omits the reasoning block on the driven path when the thinking is whitespace (isaac-ddls)"
    (let [out (str/join "\n"
                        [(json/generate-string
                           {:type "content_block_delta" :delta {:type "thinking_delta" :thinking "\n"}})
                         (json/generate-string
                           {:type "content_block_delta" :delta {:type "thinking_delta" :thinking "  "}})
                         (json/generate-string {:type "result" :result "answered"})])
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          res (sut/chat {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                        "claude" {:command "claude" :drives-tool-loop? true})]
      (should= "answered" (:content res))
      (should= nil (:reasoning res))))

  (it "streams deltas through stream-response"
    (let [out (str/join "\n"
                        (map #(json/generate-string
                                {:type "content_block_delta" :delta {:text %}})
                             ["Hello" " " "world"]))
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          p   (sut/make "claude" {:command "claude" :stream-non-tool-turns true})
          chunks (atom [])]
      (drive-turn/stream-response! p {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                                   (fn [piece] (swap! chunks conj piece)))
      (should= ["Hello" " " "world"] @chunks)))

  (it "forwards extra-args in argv"
    (let [_ (sut/clear-invocations!)
          _ (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                      "claude"
                      {:command "claude" :extra-args ["--foo" "bar"]})
          argv (:argv (first (sut/invocations)))]
      (let [idx (.indexOf argv "--foo")]
        (should= "bar" (nth argv (inc idx))))))

  (it "passes soul text via --system-prompt, not the conversation prompt"
    (sut/clear-invocations!)
    (sut/chat {:model    "sonnet"
               :messages [{:role "system" :content "Be wise."}
                          {:role "user" :content "yo"}]}
              "claude" {:command "claude"})
    (let [inv    (first (sut/invocations))
          argv   (:argv inv)
          idx    (.indexOf argv "--system-prompt")
          system (when (<= 0 idx) (nth argv (inc idx)))]
      (should (str/includes? system "Be wise."))
      (should-not (some #(str/includes? (str %) "User: yo") argv))
      (should= "User: yo" (:in inv))))

  (it "puts the conversation on stdin so large transcripts do not blow ARG_MAX"
    (sut/clear-invocations!)
    (let [body (apply str (repeat 100 "the reef is long. "))]
      (sut/chat {:model "sonnet" :messages [{:role "user" :content body}]}
                "claude" {:command "claude"})
      (let [inv (first (sut/invocations))]
        (should (str/includes? (:in inv) body))
        (should-not (some #(str/includes? (str %) body) (:argv inv))))))

  (it "puts the tool protocol contract in --system-prompt when tools are present"
    (sut/clear-invocations!)
    (sut/chat {:model    "sonnet"
               :messages [{:role "system" :content "Be wise."}
                          {:role "user" :content "run it"}]
               :tools    [{:type "function" :function {:name "exec"}}]}
              "claude" {:command "claude"})
    (let [inv    (first (sut/invocations))
          argv   (:argv inv)
          idx    (.indexOf argv "--system-prompt")
          system (when (<= 0 idx) (nth argv (inc idx)))]
      (should (str/includes? system sut/tool-protocol-contract))
      (should (str/includes? system "## Tools"))
      (should-not (str/includes? (str (:in inv)) sut/tool-protocol-contract))
      (should-not (str/includes? (str (:in inv)) "## Tools"))))

  (it "parses native invoke syntax as a tool call"
    (let [text "<invoke name=\"exec__run\"><parameter name=\"command\">echo drift</parameter></invoke>"
          res  (#'sut/success-response "sonnet" text {})
          call (first (:tool-calls res))]
      (should= :tool-use (:stop-reason res))
      (should= "exec__run" (:name call))
      (should= {:command "echo drift"} (:arguments call))))

  (it "parses a bare JSON call in a markdown fence"
    (let [text "```{\"name\":\"exec__run\",\"arguments\":{\"command\":\"echo fenced\"}}```"
          call (first (:tool-calls (#'sut/success-response "sonnet" text {})))]
      (should= "exec__run" (:name call))
      (should= {:command "echo fenced"} (:arguments call))))

  (it "drops all model text after the first parsed call"
    (let [text "before<tool_call>{\"name\":\"exec__run\",\"arguments\":{}}</tool_call>fabricated"
          res  (#'sut/success-response "sonnet" text {})]
      (should= "before" (:content res))))

  (it "parses fence and invoke calls in source order"
    (let [text  (str "<tool_call>{\"name\":\"exec__run\",\"arguments\":{\"command\":\"one\"}}</tool_call>"
                     " then <invoke name=\"exec__run\"><parameter name=\"command\">two</parameter></invoke>")
          calls (:tool-calls (#'sut/success-response "sonnet" text {}))]
      (should= ["one" "two"] (mapv #(get-in % [:arguments :command]) calls))))

  (it "parses JSON-looking invoke parameter values"
    (let [text "<invoke name=\"exec__run\"><parameter name=\"limit\">2</parameter></invoke>"
          call (first (:tool-calls (#'sut/success-response "sonnet" text {})))]
      (should= {:limit 2} (:arguments call))))

  (it "returns malformed fences as tool protocol errors instead of throwing"
    (let [text "<tool_call>{\"name\":\"exec__run\",\"arguments\":{bad}}</tool_call>"
          res  (#'sut/success-response "sonnet" text {})]
      (should= :tool-protocol (:error res))
      (should (str/includes? (:message res) "could not be parsed"))))

  (it "returns unclosed invoke blocks as tool protocol errors"
    (let [text "<invoke name=\"exec__run\"><parameter name=\"command\">one</invoke>"
          res  (#'sut/success-response "sonnet" text {})]
      (should= :tool-protocol (:error res))
      (should (:unavailable? res))))

  (it "retries a malformed fence once, then executes the well-formed call"
    (let [calls (atom 0)]
      (sut/clear-invocations!)
      (sut/set-stub!
        (fn [_]
          (swap! calls inc)
          (if (= 1 @calls)
            {:exit 0
             :out  (json/generate-string {:type "result" :result "<tool_call>{\"name\":\"exec__run\",\"arguments\":{bad}}</tool_call>"})
             :err  ""}
            {:exit 0
             :out  (json/generate-string {:type "result" :result "<tool_call>{\"name\":\"exec__run\",\"arguments\":{\"command\":\"echo fixed\"}}</tool_call>"})
             :err  ""})))
      (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "run it"}]}
                          "claude" {:command "claude"})]
        (should= 2 @calls)
        (should= "exec__run" (:name (first (:tool-calls res))))
        (should= {:command "echo fixed"} (:arguments (first (:tool-calls res)))))))

  (it "retries a malformed fence on the stream path the same way"
    (let [calls (atom 0)]
      (sut/clear-invocations!)
      (sut/set-stub!
        (fn [_]
          (swap! calls inc)
          (if (= 1 @calls)
            {:exit 0
             :out  (json/generate-string {:type "result" :result "<tool_call>{\"name\":\"exec__run\",\"arguments\":{bad}}</tool_call>"})
             :err  ""}
            {:exit 0
             :out  (json/generate-string {:type "result" :result "<tool_call>{\"name\":\"exec__run\",\"arguments\":{\"command\":\"echo fixed\"}}</tool_call>"})
             :err  ""})))
      (let [res (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "run it"}]}
                                 (fn [_])
                                 "claude" {:command "claude"})]
        (should= 2 @calls)
        (should= "exec__run" (:name (first (:tool-calls res))))
        (should= {:command "echo fixed"} (:arguments (first (:tool-calls res)))))))

  (it "suppresses all tools with --tools \"\" and never emits the bad flags"
    (sut/clear-invocations!)
    (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
              "claude" {:command "claude"})
    (let [argv (:argv (first (sut/invocations)))
          idx  (.indexOf argv "--tools")]
      (should (<= 0 idx))
      (should= "" (nth argv (inc idx)))
      (should (neg? (.indexOf argv "--disallowed-tools")))
      (should (neg? (.indexOf argv "--max-turns")))))

  (it "classifies a login failure as auth-unavailable and reports it loudly"
    (sut/set-stub! (constantly {:exit 1 :out "" :err "Not logged in · Please run /login"}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                        "claude" {:command "claude"})]
      (should= :llm-error (:error res))
      (should (str/includes? (:message res) "Please run /login"))
      (should (:unavailable? res))
      (should= :auth (:reason res))))

  (it "reports a nonzero exit as a loud error without auth misclassification"
    (sut/set-stub! (constantly {:exit 1 :out "" :err "claude: boom"}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                        "claude" {:command "claude"})]
      (should= :llm-error (:error res))
      (should (str/includes? (:message res) "claude: boom"))
      (should-not (:unavailable? res))))

  (it "parses json result text and usage"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type   "result"
                                                :result "answer"
                                                :usage  {:input_tokens  50
                                                         :output_tokens 9
                                                         :cache_read_input_tokens      2
                                                         :cache_creation_input_tokens 1}})
                   :err  ""}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude" {:command "claude"})]
      (should= "answer" (:content res))
      (should= 53 (:prompt-tokens (:usage res)))
      (should= 9 (:output-tokens (:usage res)))
      (should= 2 (:cache-read-tokens (:usage res)))
      (should= 1 (:cache-write-tokens (:usage res)))))

  (it "degrades to zero usage when json omits usage"
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type "result" :result "ok"})
                   :err  ""}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude" {:command "claude"})]
      (should= "ok" (:content res))
      (should= 0 (:prompt-tokens (:usage res)))
      (should= 0 (:output-tokens (:usage res)))))

  (it "uses stream-json terminal result usage"
    (let [out (str/join "\n"
                        [(json/generate-string {:type "content_block_delta" :delta {:text "Hi"}})
                         (json/generate-string {:type   "result"
                                                :result "Hi"
                                                :usage  {:input_tokens 10 :output_tokens 2}})])
          _   (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          res (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                               (fn [_]) "claude" {:command "claude" :stream-non-tool-turns true})]
      (should= "Hi" (:content res))
      (should= 10 (:prompt-tokens (:usage res)))
      (should= 2 (:output-tokens (:usage res))))))

(describe "claude-cli persisted transcript usage (isaac-l70j)"
  (marigold.agent/with-manifest)

  (around [example]
    (nexus/-with-nexus {:root transcript-test-dir :fs (fs/mem-fs)}
      (session-helper/with-memory-store
        (example))))

  (it "non-stream chat persists nonzero usage on the assistant transcript entry"
    (session-helper/create-session! transcript-test-dir "claude-ns-usage")
    (sut/set-stub!
      (constantly {:exit 0
                   :out  (json/generate-string {:type   "result"
                                                :result "4"
                                                :usage  {:input_tokens 120 :output_tokens 15}})
                   :err  ""}))
    (let [res (sut/chat {:model "sonnet" :messages [{:role "user" :content "yo"}]}
                        "claude"
                        {:command "claude"})]
      (drive-turn/process-response! "claude-ns-usage"
                                    {:response res :usage (assoc (:usage res) :requests 1)}
                                    {:model "sonnet" :provider "claude"})
      (let [assistant (-> (session-helper/get-transcript transcript-test-dir "claude-ns-usage")
                          last
                          :message)
            usage (:usage assistant)]
        (should (pos? (:prompt-tokens usage)))
        (should (pos? (:output-tokens usage))))))

  (it "streaming terminal usage persists nonzero fields on the assistant transcript entry"
    (session-helper/create-session! transcript-test-dir "claude-stream-usage")
    (let [out (str/join "\n"
                        [(json/generate-string {:type "content_block_delta" :delta {:text "Hi"}})
                         (json/generate-string {:type   "result"
                                                :result "Hi"
                                                :usage  {:input_tokens 88 :output_tokens 11}})])]
      (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
      (let [res (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                                 (fn [_])
                                 "claude"
                                 {:command "claude" :stream-non-tool-turns true})]
        (drive-turn/process-response! "claude-stream-usage"
                                      {:response res :usage (assoc (:usage res) :requests 1)}
                                      {:model "sonnet" :provider "claude"})
        (let [assistant (-> (session-helper/get-transcript transcript-test-dir "claude-stream-usage")
                            last
                            :message)
              usage (:usage assistant)]
          (should (pos? (:prompt-tokens usage)))
          (should (pos? (:output-tokens usage))))))))