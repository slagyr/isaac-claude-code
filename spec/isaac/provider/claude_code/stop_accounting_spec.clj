(ns isaac.provider.claude-code.stop-accounting-spec
  "A Stop is the user's choice, not provider weather. It must come back as a
   cancelled result, never :mcp-unavailable / :unavailable?, and it must still
   carry what the API billed, including a request that never reached its
   first assistant event. Real /bin/sh stand-ins for claude."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [c3kit.apron.schema :as schema]
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.provider.claude-code.api.claude-cli :as sut]
    [speclj.core :refer [after before describe it should= should-not should-be-nil]]))

(defn- sh-quote [s] (str "'" (str/replace s "'" "'\\''") "'"))
(defn- line [m] (json/generate-string m))
(defn- text-line [s] (line {:type "stream_event" :event {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text s}}}))
(def ^:private message-start
  (line {:type "stream_event" :event {:type "message_start"
                                      :message {:id "msg_1" :usage {:input_tokens 1200 :output_tokens 1
                                                                    :cache_read_input_tokens 300}}}}))
(def ^:private result-line (line {:type "result" :result "done" :usage {:input_tokens 1200 :output_tokens 300}}))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "claude-stop" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- rm-rf [f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf c)))
  (.delete f))
(def ^:private dir (atom nil))

(defn- script! [name body]
  (let [f (io/file @dir name)]
    (spit f (str "#!/bin/sh\n" body))
    (.setExecutable f true)
    (.getPath f)))

(defn- emit [& lines] (str/join "\n" (map #(str "printf '%s\\n' " (sh-quote %)) lines)))

(defn- cancel-on-first-chunk! [request command session-key]
  (bridge-cancel/begin-turn! session-key)
  (try
    (sut/chat-stream request
                     (fn [{:keys [text-delta]}]
                       (when text-delta (bridge-cancel/cancel! session-key)))
                     "claude"
                     {:command command :stream-non-tool-turns true :forward-env [] :session-key session-key})
    (finally (bridge-cancel/clear!))))

(defn- await-file! [file]
  ;; Script readiness replaces a fixed delay: never Stop before the child has
  ;; received stdin and reached the state this example claims to exercise.
  (let [deadline (+ (System/nanoTime) 3000000000)]
    (loop []
      (cond
        (.exists file) nil
        (< (System/nanoTime) deadline) (do (Thread/sleep 5) (recur))
        :else (throw (ex-info "Claude script never became ready" {:file file}))))))

(defn- cancel-after! [ready f session-key]
  (bridge-cancel/begin-turn! session-key)
  (try
    (let [fut (future (f))]
      (await-file! ready)
      (bridge-cancel/cancel! session-key)
      (let [res (deref fut 3000 ::timeout)]
        (should-not (= ::timeout res))
        res))
    (finally (bridge-cancel/clear!))))

(def ^:private plain {:model "haiku" :messages [{:role "user" :content "hi"}]})
(def ^:private driven (assoc plain :tools [{:name "read" :description "r" :parameters {:type "object"}}]))

(defn- should-be-cancelled [res]
  (should= :cancelled (:error res))
  (should= true (:cancelled? res))
  (should-not (:unavailable? res))
  (should-be-nil (:reason res))
  ;; It is checked against the agent's error contract (dispatch validates
  ;; every non-weather error), so it must pass it or become :provider-contract.
  (should-not (schema/error? (api/validate-error res))))

(def ^:private mid-stream
  (str "cat >/dev/null\n" (emit message-start (text-line "partial")) "\nsleep 20 & wait $!\n"))

(describe "stop accounting"
  (before (sut/clear-stub!) (sut/clear-invocations!) (sut/clear-fake-cli!) (bridge-cancel/clear!) (reset! dir (temp-dir)))
  (after (bridge-cancel/clear!) (rm-rf @dir))

  (describe "a Stop is a cancelled result, not weather"

    (it "on a driven turn"
      (should-be-cancelled (cancel-on-first-chunk! driven (script! "c" mid-stream) "driven")))

    (it "on a plain streamed turn"
      (should-be-cancelled (cancel-on-first-chunk! plain (script! "c" mid-stream) "plain")))

    (it "on a plain non-streamed turn"
      (let [ready   (io/file @dir "json-ready")
            command (script! "c" (str "cat >/dev/null\ntouch " (sh-quote (.getPath ready)) "\nsleep 20 & wait $!\n"))]
        (should-be-cancelled
          (cancel-after! ready #(sut/chat plain "claude" {:command command :forward-env [] :session-key "json"}) "json"))))

    (it "before claude prints anything"
      (let [ready   (io/file @dir "early-ready")
            command (script! "c" (str "cat >/dev/null\ntouch " (sh-quote (.getPath ready)) "\nsleep 20 & wait $!\n"))
            res     (cancel-after! ready #(sut/chat-stream driven (fn [_]) "claude"
                                                           {:command command :forward-env [] :session-key "early"})
                                     "early")]
        (should-be-cancelled res)
        (should= 0 (get-in res [:usage :prompt-tokens]))))

    (it "even when claude's stderr names a usage limit as it dies"
      ;; Weather classification reads stderr; a Stop must win over it, or a
      ;; cancel could wall the provider and walk the crew's model chain.
      (let [command (script! "c" (str "cat >/dev/null\n"
                                      "echo 'Claude AI usage limit reached' >&2\n"
                                      (emit message-start (text-line "partial"))
                                      "\nsleep 20 & wait $!\n"))]
        (should-be-cancelled (cancel-on-first-chunk! driven command "limit"))))

    (it "keeps the text that streamed before the Stop"
      (should= "partial" (:content (cancel-on-first-chunk! driven (script! "c" mid-stream) "text")))))

  (describe "a Stop carries what the API billed"

    (it "reads message_start usage when no assistant event arrived"
      (let [res (cancel-on-first-chunk! driven (script! "c" mid-stream) "usage")]
        (should= 1500 (get-in res [:usage :prompt-tokens]))
        (should= 300 (get-in res [:usage :cache-read-tokens]))
        (should= 1 (get-in res [:usage :output-tokens]))
        (should= 1 (count (:cycle-usages res)))))

    (it "on a plain turn too"
      (let [res (cancel-on-first-chunk! plain (script! "c" mid-stream) "usage-plain")]
        (should= 1500 (get-in res [:usage :prompt-tokens]))))

    (it "counts message_start and its assistant event as one request"
      (let [assistant (line {:type "assistant" :message {:id "msg_1" :content [{:type "text" :text "partial"}]
                                                         :usage {:input_tokens 1200 :output_tokens 40
                                                                 :cache_read_input_tokens 300}}})
            command   (script! "c" (str "cat >/dev/null\n" (emit message-start assistant (text-line "more"))
                                        "\nsleep 20 & wait $!\n"))
            res       (cancel-on-first-chunk! driven command "one-request")]
        (should= 1 (count (:cycle-usages res)))
        (should= 1500 (get-in res [:usage :prompt-tokens]))
        (should= 40 (get-in res [:usage :output-tokens]))))

    (it "a cancel after the result event keeps the finished request's usage"
      (let [ready   (io/file @dir "after-ready")
            command (script! "c" (str "cat >/dev/null\n" (emit message-start (text-line "whole answer") result-line)
                                      "\ntouch " (sh-quote (.getPath ready)) "\nsleep 20 & wait $!\n"))
            res     (cancel-after! ready #(sut/chat-stream driven (fn [_]) "claude"
                                                           {:command command :forward-env [] :session-key "after"})
                                     "after")]
        (should-be-cancelled res)
        (should= 1500 (get-in res [:usage :prompt-tokens])))))

  (describe "the driver"

    (it "hands a cancelled response up with its usage"
      (let [response {:error :cancelled :cancelled? true :content "partial"
                      :usage {:prompt-tokens 1500 :output-tokens 1} :cycle-usages [{:prompt-tokens 1500 :output-tokens 1}]}
            stopped  (atom false)
            chat-fn  (fn [_] (reset! stopped true) response)
            res      (sut/claude-loop-driver chat-fn nil driven (fn [& _]) {:cancelled? #(deref stopped)})]
        (should= :cancelled (:error res))
        (should= true (:cancelled? res))
        (should= 1500 (get-in res [:usage :prompt-tokens]))))))
