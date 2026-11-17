(ns isaac.provider.claude-code.live-stream-spec
  "Deltas reach on-chunk while `claude` is still running, not after it exits.
   A real subprocess stands in for Claude Code: it prints one delta, then
   waits for the marker file the on-chunk callback writes. Only a provider
   that forwards deltas live lets it see the marker."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.llm.tool-loop :as tool-loop]
    [isaac.provider.claude-code.api.claude-cli :as sut]
    [speclj.core :refer [after before describe it should should= should-not]]))

(defn- delta-line [delta]
  (json/generate-string {:type "stream_event" :event {:type "content_block_delta" :index 0 :delta delta}}))

(defn- text-line [s] (delta-line {:type "text_delta" :text s}))
(defn- thinking-line [s] (delta-line {:type "thinking_delta" :thinking s}))

(defn- sh-quote [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defn- fake-claude!
  "Write an executable that drains stdin, prints `first-lines`, waits up to
   3 s for `marker`, then prints `live-lines` if it appeared or
   `buffered-lines` if not, and finally a result event."
  [dir marker first-lines live-lines buffered-lines]
  (let [script (io/file dir "fake-claude")
        emit   (fn [lines] (str/join "\n" (map #(str "printf '%s\\n' " (sh-quote %)) lines)))]
    (spit script
          (str "#!/bin/sh\n"
               "cat >/dev/null\n"
               (emit first-lines) "\n"
               "i=0\n"
               "while [ ! -f " (sh-quote marker) " ] && [ $i -lt 30 ]; do sleep 0.1; i=$((i+1)); done\n"
               "if [ -f " (sh-quote marker) " ]; then\n" (emit live-lines) "\nelse\n" (emit buffered-lines) "\nfi\n"
               (emit [(json/generate-string {:type "result" :result "" :usage {:input_tokens 1 :output_tokens 2}})])
               "\n"))
    (.setExecutable script true)
    (.getPath script)))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "claude-live" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf [f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf c)))
  (.delete f))

(def ^:private dir (atom nil))

(defn- stream! [command on-chunk]
  (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                   on-chunk "claude"
                   {:command command :stream-non-tool-turns true :forward-env []}))

(describe "claude-cli live streaming"
  (before (sut/clear-stub!) (sut/clear-invocations!) (sut/clear-fake-cli!) (reset! dir (temp-dir)))
  (after (rm-rf @dir))

  (it "forwards a text delta to on-chunk before the process exits"
    (let [marker  (.getPath (io/file @dir "seen"))
          command (fake-claude! @dir marker [(text-line "Three ")] [(text-line "moorings,") (text-line " all") (text-line " sound.")] [(text-line " buffered")])
          chunks  (atom [])
          res     (stream! command (fn [{:keys [text-delta]}]
                                     (when text-delta
                                       (swap! chunks conj text-delta)
                                       (spit marker "x"))))]
      (should= ["Three " "moorings," " all" " sound."] @chunks)
      (should= "Three moorings, all sound." (:content res))))

  (it "forwards a thinking delta live, ahead of the text that follows it"
    (let [marker  (.getPath (io/file @dir "seen"))
          command (fake-claude! @dir marker [(thinking-line "hmm")] [(text-line "live")] [(text-line "buffered")])
          seen    (atom [])
          res     (stream! command (fn [chunk]
                                     (swap! seen conj chunk)
                                     (when (:reasoning-delta chunk) (spit marker "x"))))]
      (should= [{:reasoning-delta "hmm"} {:text-delta "live"}] @seen)
      (should= "live" (:content res))
      (should= {:summary "hmm"} (:reasoning res))))

  (it "keeps the full stdout for usage once the stream ends"
    (let [marker  (.getPath (io/file @dir "seen"))
          command (fake-claude! @dir marker [(text-line "a")] [(text-line "b")] [(text-line "c")])
          res     (stream! command (fn [_] (spit marker "x")))]
      (should= 2 (get-in res [:usage :output-tokens]))))

  (it "emits each delta exactly once"
    (let [marker  (.getPath (io/file @dir "seen"))
          command (fake-claude! @dir marker [(text-line "x") (text-line "y")] [(text-line "z")] [(text-line "buffered")])
          chunks  (atom [])]
      (stream! command (fn [{:keys [text-delta]}]
                         (when text-delta (swap! chunks conj text-delta) (spit marker "x"))))
      (should= ["x" "y" "z"] @chunks)
      (should-not (some #{"buffered"} @chunks))))

  (it "a cancel mid-stream stops the read instead of waiting for the process"
    (let [script (io/file @dir "slow-claude")
          _      (spit script (str "#!/bin/sh\ncat >/dev/null\nprintf '%s\\n' " (sh-quote (text-line "partial")) "\nexec sleep 20\n"))
          _      (.setExecutable script true)
          _      (bridge-cancel/begin-turn! "live-cancel")
          chunks (atom [])
          start  (System/currentTimeMillis)]
      (try
        (sut/chat-stream {:model "sonnet" :messages [{:role "user" :content "hi"}]}
                         (fn [{:keys [text-delta]}]
                           (when text-delta
                             (swap! chunks conj text-delta)
                             (bridge-cancel/cancel! "live-cancel")))
                         "claude"
                         {:command (.getPath script) :stream-non-tool-turns true :forward-env [] :session-key "live-cancel"})
        (finally (bridge-cancel/clear!)))
      (should= ["partial"] @chunks)
      (should (< (- (System/currentTimeMillis) start) 5000)))))


(defn- se [event] {:type "stream_event" :event event})
(defn- usage [out] {:input_tokens 3 :cache_read_input_tokens 100 :cache_creation_input_tokens 20 :output_tokens out})

(def ^:private tool-turn
  "One driven turn: an aside, a tool call, the tool result, the reply."
  [(se {:type "message_start" :message {:id "A" :usage (usage 1)}})
   (se {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Checking."}})
   {:type "assistant" :message {:id "A" :role "assistant" :usage (usage 1)
                                :content [{:type "text" :text "Checking."}
                                          {:type "tool_use" :id "t1" :name "mcp__isaac__fs__read" :input {:path "probe.txt"}}]}}
   (se {:type "message_delta" :usage (usage 9)})
   (se {:type "message_stop"})
   {:type "user" :message {:role "user" :content [{:type "tool_result" :tool_use_id "t1" :content "kumquat-42"}]}}
   (se {:type "message_start" :message {:id "B" :usage (usage 1)}})
   (se {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "kumquat-42"}})
   {:type "assistant" :message {:id "B" :role "assistant" :usage (usage 1) :content [{:type "text" :text "kumquat-42"}]}}
   (se {:type "message_delta" :usage (usage 4)})
   (se {:type "message_stop"})
   {:type "result" :is_error false :stop_reason "end_turn" :result "kumquat-42" :usage (usage 13)}])

(defn- isaac-stream-chat-fn
  "What Isaac's drive does on the streaming path (turn/stream-response!):
   forward chunks, then replace :content with every text delta joined."
  [p chunks]
  (fn [req]
    (let [full (atom "")
          res  (api/chat-stream p req (fn [{:keys [text-delta]}]
                                        (when (seq text-delta)
                                          (swap! chunks conj text-delta)
                                          (swap! full str text-delta))))]
      (assoc res :content (or (not-empty @full) (:content res))))))

(describe "claude-cli driven turn on the streaming path"
  (before (sut/clear-stub!) (sut/clear-invocations!) (sut/clear-fake-cli!) (tool-loop/clear-provider-driver!))
  (after (sut/clear-stub!) (sut/clear-invocations!) (sut/clear-fake-cli!) (tool-loop/clear-provider-driver!))

  (it "streams the aside and the reply, but the stored reply is the last cycle's text only"
    (sut/set-stub! (constantly {:exit 0 :out (str/join "\n" (map json/generate-string tool-turn)) :err ""}))
    (let [p      (sut/make "claude" {:command "claude"})
          chunks (atom [])
          res    (sut/claude-loop-driver (isaac-stream-chat-fn p chunks) nil
                                         {:model "sonnet" :tools [{:type "function" :function {:name "fs__read"}}]
                                          :messages [{:role "user" :content "go"}]}
                                         (fn [& _] "kumquat-42") {})]
      (should= ["Checking." "kumquat-42"] @chunks)
      (should= "kumquat-42" (get-in res [:response :content]))
      (should= 1 (count (sut/invocations)))
      (should= 1 (count (str/split-lines (:in (first (sut/invocations))))))
      (should= 13 (get-in res [:usage :output-tokens]))))

  (it "streaming a tool-bearing turn costs no extra CLI invocation or usage"
    (let [request {:model "sonnet" :tools [{:type "function" :function {:name "fs__read"}}]
                   :messages [{:role "user" :content "hello"}]}
          out     (str/join "\n" (map json/generate-string
                                          [(se {:type "content_block_delta" :delta {:type "text_delta" :text "Three "}})
                                           (se {:type "content_block_delta" :delta {:type "text_delta" :text "moorings,"}})
                                           (se {:type "content_block_delta" :delta {:type "text_delta" :text " all"}})
                                           (se {:type "content_block_delta" :delta {:type "text_delta" :text " sound."}})
                                           {:type "result" :result "Three moorings, all sound." :usage (usage 9)}]))
          _       (sut/set-stub! (constantly {:exit 0 :out out :err ""}))
          p       (sut/make "claude" {:command "claude"})
          plain   (api/chat p request)
          plain-in (mapv :in (sut/invocations))
          _       (sut/clear-invocations!)
          chunks  (atom [])
          streamed (api/chat-stream p request #(swap! chunks conj (:text-delta %)))]
      (should= ["Three " "moorings," " all" " sound."] @chunks)
      (should= "Three moorings, all sound." (:content streamed))
      (should= (:usage plain) (:usage streamed))
      (should= plain-in (mapv :in (sut/invocations)))))

  (it "the claude-code template lets Isaac take the streaming path for tool turns"
    (let [manifest (read-string (slurp (io/resource "isaac-manifest.edn")))]
      (should-not (false? (get-in manifest [:isaac.agent/provider-template :claude-code :template :stream-supports-tool-calls]))))))
