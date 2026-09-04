(ns isaac.llm.api.claude-cli
  (:require
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.followup :as followup]
    [isaac.llm.prompt.builder :as prompt]))

;; region ----- Test Hooks -----

(defonce ^:private stub-state* (atom nil))
(defonce ^:private invocations* (atom []))

(defn- record-invocation! [invocation]
  (swap! invocations* conj invocation))

(defn set-stub! [f]
  (reset! stub-state* f))

(defn clear-stub! []
  (reset! stub-state* nil))

(defn clear-invocations! []
  (reset! invocations* []))

(defn invocations []
  (vec @invocations*))

;; endregion ^^^^^ Test Hooks ^^^^^

;; region ----- Prompt / Response -----

(def ^:private tool-call-open "<tool_call>")
(def ^:private tool-call-close "</tool_call>")

(def tool-protocol-contract
  "You have no built-in tools. To act, emit <tool_call>{\"name\":\"<tool>\",\"arguments\":{...}}</tool_call> exactly as written; the harness executes it and returns results in a follow-up turn.")

(defn- content->text [content]
  (cond
    (string? content) content
    (vector? content) (->> content
                           (filter #(= "text" (:type %)))
                           (map :text)
                           (str/join))
    :else (str content)))

(defn- system-messages [request]
  (filter #(= "system" (:role %)) (:messages request)))

(defn- conversation-messages [request]
  (remove #(= "system" (:role %)) (:messages request)))

(defn- system-text [request]
  (->> (system-messages request)
       (map #(content->text (:content %)))
       (remove str/blank?)
       (str/join "\n\n")))

(defn- tool-defs-text [request]
  (when (seq (:tools request))
    (str "## Tools\n" (json/generate-string (:tools request)))))

(defn- build-system-prompt [request]
  (str/join "\n\n"
            (remove str/blank?
                    [(system-text request)
                     (when (seq (:tools request)) tool-protocol-contract)
                     (tool-defs-text request)])))

(defn- conversation->prompt-text [request]
  (let [role-lines (for [{:keys [role content]} (conversation-messages request)]
                     (str (str/capitalize (name role)) ": " (content->text content)))]
    (str/join "\n\n" (remove str/blank? role-lines))))

(defn- tool-call-text [name args]
  (str tool-call-open (json/generate-string {:name name :arguments args}) tool-call-close))

(defn- parse-tool-calls [text]
  (loop [remaining (or text "")
         calls     []]
    (if-let [start (str/index-of remaining tool-call-open)]
      (let [after-open (subs remaining (+ start (count tool-call-open)))
            end        (str/index-of after-open tool-call-close)]
        (if end
          (let [payload (subs after-open 0 end)
                parsed  (json/parse-string payload true)]
            (recur (subs after-open (+ end (count tool-call-close)))
                   (conj calls {:id        (str (java.util.UUID/randomUUID))
                                :name      (:name parsed)
                                :arguments (or (:arguments parsed) {})
                                :raw       parsed})))
          calls))
      calls)))

(defn- visible-text [text]
  (let [open-idx (str/index-of (or text "") tool-call-open)]
    (if open-idx (subs text 0 open-idx) text)))

(defn- zero-usage []
  {:input-tokens 0 :output-tokens 0 :cache-read 0 :cache-write 0})

(defn- parse-cli-usage [usage]
  (when (and (map? usage)
             (or (contains? usage :input_tokens)
                 (contains? usage :output_tokens)
                 (contains? usage :cache_read_input_tokens)
                 (contains? usage :cache_creation_input_tokens)))
    {:input-tokens  (or (:input_tokens usage) 0)
     :output-tokens (or (:output_tokens usage) 0)
     :cache-read    (or (:cache_read_input_tokens usage) 0)
     :cache-write   (or (:cache_creation_input_tokens usage) 0)}))

(defn- normalize-usage [usage]
  (cond
    (not (map? usage)) (zero-usage)
    (contains? usage :input-tokens) usage
    :else (or (parse-cli-usage usage) (zero-usage))))

(defn- success-response [model text usage]
  (let [tool-calls (parse-tool-calls text)
        content    (visible-text text)
        usage*     (normalize-usage usage)]
    {:message    (cond-> {:role "assistant" :content content}
                   (seq tool-calls) (assoc :tool_calls tool-calls))
     :model      model
     :tool-calls tool-calls
     :usage      usage*}))

(defn- parse-json-output [out]
  (try
    (let [parsed (json/parse-string (str/trim (or out "")) true)]
      (if (= "result" (:type parsed))
        {:text  (str (or (:result parsed) ""))
         :usage (normalize-usage (:usage parsed))}
        {:text (or out "") :usage (zero-usage)}))
    (catch Exception _
      {:text (or out "") :usage (zero-usage)})))

(def ^:private auth-failure-re
  #"(?i)not logged in|please run\s*/login|invalid api key|not authenticated|no credentials|unauthorized")

(defn- auth-failure? [out err]
  (boolean (re-find auth-failure-re (str (or out "") "\n" (or err "")))))

(defn- error-message [result]
  (or (not-empty (str/trim (str (:err result))))
      (not-empty (str/trim (str (:out result))))
      "claude binary failed"))

(defn- error-response
  "Loud on the prompt path (:error + :message) AND classified for hail
   defer+attention when the failure is auth ({:unavailable? true :reason :auth},
   the provider_wall convention). (isaac-kn7y)"
  [result]
  (cond-> {:error :llm-error :message (error-message result)}
    (auth-failure? (:out result) (:err result))
    (assoc :unavailable? true :reason :auth)))

(defn- failed?
  "A run failed when the process exited nonzero OR the output is a login/auth
   failure that the CLI otherwise reports on a zero exit (the empty-success
   disease this bean fixes)."
  [result]
  (or (not (zero? (:exit result)))
      (auth-failure? (:out result) (:err result))))

;; endregion ^^^^^ Prompt / Response ^^^^^

;; region ----- CLI Invocation -----

(defn- flag-args [streaming?]
  ;; `--tools ""` disables ALL built-in tools (the real CLI flag; the prior
  ;; `--disallowed-tools all` denied a tool literally named "all" — i.e. nothing,
  ;; and warned). With no tools there is no tool loop, so the --print run is a
  ;; pure completion; `--max-turns` does not exist in this CLI version. Isaac
  ;; owns the transcript, so session persistence stays off. (isaac-kn7y)
  (cond-> ["--print"
           "--output-format" (if streaming? "stream-json" "json")]
    streaming? (conj "--include-partial-messages")
    :always (into ["--tools" ""
                   "--no-session-persistence"])))

(defn- extra-args [cfg]
  (vec (or (:extra-args cfg) (:extraArgs cfg) [])))

(defn- command-path [cfg]
  (or (:command cfg) "claude"))

(defn- subprocess-env []
  (dissoc (into {} (System/getenv)) "ANTHROPIC_API_KEY"))

(defn- build-argv [cfg request streaming?]
  (let [system (build-system-prompt request)
        base   (into [(command-path cfg)]
                     (concat (extra-args cfg)
                             (flag-args streaming?)
                             ["--model" (:model request)]))]
    (if (seq (str/trim system))
      (into base ["--system-prompt" system])
      base)))

(defn- run-process! [argv env in]
  (if-let [stub @stub-state*]
    (stub {:argv argv :env env :in in})
    (let [result @(process/process argv {:env env :err :string :out :string :in (or in "")})]
      {:exit (:exit result)
       :out  (:out result)
       :err  (:err result)})))

(defn- invoke! [cfg request streaming?]
  (let [argv   (build-argv cfg request streaming?)
        prompt (conversation->prompt-text request)
        env    (subprocess-env)]
    (record-invocation! {:argv argv :env env :in prompt})
    (run-process! argv env prompt)))

(defn- stream-json-event [line]
  (when (seq (str/trim line))
    (try
      (json/parse-string line true)
      (catch Exception _ nil))))

(defn- stream-json-delta-text [event]
  (cond
    (get-in event [:delta :text])
    (get-in event [:delta :text])

    (get-in event [:content_block_delta :delta :text])
    (get-in event [:content_block_delta :delta :text])

    (get-in event [:message :content 0 :text])
    (get-in event [:message :content 0 :text])

    (string? (:text event))
    (:text event)

    :else nil))

(defn- parse-stream-json-output [lines]
  (loop [deltas []
         usage  (zero-usage)
         [line & more] lines]
    (if line
      (let [event (stream-json-event line)]
        (recur (if-let [t (when event (stream-json-delta-text event))]
                 (conj deltas t)
                 deltas)
               (if (= "result" (:type event))
                 (normalize-usage (:usage event))
                 usage)
               more))
      {:deltas deltas :usage usage})))

;; endregion ^^^^^ CLI Invocation ^^^^^

;; region ----- Public API -----

(defn chat [request _provider-name cfg]
  (let [result (invoke! cfg request false)]
    (if (failed? result)
      (error-response result)
      (let [{:keys [text usage]} (parse-json-output (:out result))]
        (success-response (:model request) text usage)))))

(defn chat-stream [request on-chunk _provider-name cfg]
  (let [result (invoke! cfg request true)]
    (if-not (failed? result)
      (let [lines    (str/split-lines (:out result))
            {:keys [deltas usage]} (parse-stream-json-output lines)
            content  (str/join deltas)]
        (doseq [delta deltas]
          (when (seq delta)
            (on-chunk {:message {:role "assistant" :content delta} :done false})))
        (let [response (success-response (:model request) content usage)]
          (on-chunk {:done true})
          response))
      (error-response result))))

(defn followup-messages [request response tool-calls tool-results]
  (let [assistant-msg (or (:message response)
                          {:role "assistant" :content ""})
        result-msgs   (mapv (fn [tc result]
                              {:role    "user"
                               :content (str "Tool result for " (:name tc) ": " result)})
                            tool-calls
                            tool-results)]
    (followup/append-followup-messages request assistant-msg result-msgs)))

(deftype ClaudeCliAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build opts)))

(defn make [name cfg]
  (->ClaudeCliAPI name cfg))

;; endregion ^^^^^ Public API ^^^^^