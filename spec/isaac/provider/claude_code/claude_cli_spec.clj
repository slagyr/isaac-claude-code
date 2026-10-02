(ns isaac.provider.claude-code.claude-cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.drive.turn :as drive-turn]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.marigold.agent :as marigold.agent]
    [isaac.agent.session.spec-helper :as session-helper]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.marigold :as marigold]
    [isaac.foundation.nexus :as nexus]
    [isaac.provider.claude-code.api.claude-cli :as sut]
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
          res (sut/chat {:model "sonnet" :tools [{:type "function" :function {:name "exec__run"}}]
                        :messages [{:role "user" :content "hi"}]}
                        "claude" {:command "claude" })]
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
      (should= :auth-failed (:error res))
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

(describe "scripted Claude Code usage (isaac-6ef2)"
  (it "reports each request on its assistant message and cumulative spend on the result"
    (let [script [{:cycle 1 :kind "tool_use" :payload "{\"name\":\"exec__run\",\"input\":{}}"}
                  {:cycle 1 :kind "usage" :payload "{\"input_tokens\":10}"}
                  {:cycle 2 :kind "text" :payload "done"}
                  {:cycle 2 :kind "usage" :payload "{\"input_tokens\":20}"}]
          events (#'sut/script-events script)]
      (should= [10 20] (mapv #(get-in % [:message :usage :input_tokens])
                             (filter #(= "assistant" (:type %)) events)))
      (should= 30 (get-in (last events) [:usage :input_tokens]))))

  (it "honors an explicit result total instead of summing the assistant requests"
    (let [events (#'sut/script-events [{:cycle 1 :kind "text" :payload "done"}
                                       {:cycle 1 :kind "usage" :payload "{\"input_tokens\":1200}"}
                                       {:cycle 1 :kind "result_usage" :payload "{\"input_tokens\":802832}"}])]
      (should= 1200 (get-in (first (filter #(= "assistant" (:type %)) events))
                            [:message :usage :input_tokens]))
      (should= 802832 (get-in (last events) [:usage :input_tokens])))))

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