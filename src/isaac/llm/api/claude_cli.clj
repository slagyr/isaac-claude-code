(ns isaac.llm.api.claude-cli
  (:require
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.bridge.cancellation :as bridge-cancel]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.followup :as followup]
    [isaac.llm.prompt.builder :as prompt]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.logger :as log]))

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

(defonce ^:private fake-cli* (atom nil))
(defonce ^:private fake-cycle* (atom 0))
(defonce ^:private fail-mcp-init?* (atom false))
(defonce ^:private exit-before-stream* (atom nil))
(defonce ^:private live-process* (atom nil))
(defonce ^:private terminated?* (atom false))
(defonce ^:private stdin-messages* (atom []))
(defonce ^:private fallback-logged?* (atom false))
(defonce ^:private last-driven?* (atom false))
(defonce ^:private last-cfg* (atom {}))

(def ^:private remembered-keys
  [:drives-tool-loop? :command :extra-args :extraArgs
   :stream-non-tool-turns :streamNonToolTurns
   :stream-supports-tool-calls :streamSupportsToolCalls
   :api :auth])

(defn- full-cfg? [cfg]
  (or (contains? cfg :drives-tool-loop?)
      (contains? cfg :command)
      (contains? cfg :api)
      (contains? cfg :extra-args)
      (contains? cfg :extraArgs)
      (contains? cfg :stream-non-tool-turns)
      (contains? cfg :streamNonToolTurns)))

(defn- remember-driven! [cfg]
  ;; augment-provider remakes the Api with only session keys, dropping
  ;; :drives-tool-loop?, :command, :extra-args, and :stream-non-tool-turns.
  ;; Full configs update the memory; session-only remakes reuse it.
  (when (full-cfg? cfg)
    (reset! last-cfg* (select-keys cfg remembered-keys))
    (reset! last-driven?* (boolean (:drives-tool-loop? cfg)))))

(defn- driven-cfg [cfg]
  (remember-driven! cfg)
  (merge @last-cfg* cfg))

(defn clear-fake-cli! []
  (reset! fake-cli* nil)
  (reset! fake-cycle* 0)
  (reset! fail-mcp-init?* false)
  (reset! exit-before-stream* nil)
  (reset! live-process* nil)
  (reset! terminated?* false)
  (reset! stdin-messages* [])
  (reset! fallback-logged?* false)
  (reset! last-driven?* false)
  (reset! last-cfg* {}))

(defn fail-mcp-init! []
  (reset! fail-mcp-init?* true)
  (reset! fallback-logged?* false)
  (log/info :claude/driver-fallback :provider "claude" :reason :mcp-init)
  (reset! fallback-logged?* true))

(defn exit-before-streaming! [exit-code stderr]
  (reset! exit-before-stream* {:exit (or exit-code 1) :err (or stderr "")})
  (reset! fallback-logged?* false))

(defn fake-cli-terminated? []
  @terminated?*)

(defn- parse-stdin-line [line]
  (let [t (str/trim (or line ""))]
    (when (seq t)
      (or (try
            (let [parsed  (json/parse-string t true)
                  msg     (or (:message parsed) parsed)
                  role    (or (:role msg) (:role parsed))
                  content (or (:content msg) (:content parsed))]
              (when role
                {:role    (name role)
                 :content (cond
                            (string? content) content
                            (vector? content) (->> content (filter #(= "text" (:type %))) (map :text) (str/join))
                            :else (str content))}))
            (catch Exception _ nil))
          (when-let [[_ role content] (re-matches #"(?i)(user|assistant|system):\s*(.*)" t)]
            {:role    (str/lower-case role)
             :content content})))))

(defn fake-cli-stdin []
  (let [captured (vec @stdin-messages*)]
    (if (seq captured)
      captured
      (vec (keep parse-stdin-line (str/split-lines (or (:in (last @invocations*)) "")))))))

(defn set-fake-cli! [script]
  (reset! fake-cli* script)
  (reset! fake-cycle* 0)
  (reset! stdin-messages* [])
  (reset! terminated?* false))

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

(defn- conversation->stream-json [request]
  (->> (conversation-messages request)
       (map (fn [{:keys [role content]}]
              (json/generate-string {:role    (name role)
                                     :content (content->text content)})))
       (str/join "\n")))

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

(def ^:private STREAM-JSON-NEEDS-VERBOSE
  "Error: When using --print, --output-format=stream-json requires --verbose")

(defn- driven? [cfg]
  (boolean (and (:drives-tool-loop? cfg) (not @fail-mcp-init?*))))

(defn- maybe-log-fallback! [_cfg]
  (when (and @fail-mcp-init?*
             (compare-and-set! fallback-logged?* false true))
    (log/info :claude/driver-fallback :provider "claude" :reason :mcp-init)))

(defn- log-title-side-call! [cfg]
  ;; khgy: Claude Code has no documented flag to skip the per-invocation
  ;; title-generation side call. --no-session-persistence still writes an
  ;; ai-title stub (anthropics/claude-code#49565, #52555). Accept the cost
  ;; and log it so operators can see it per driven turn.
  (when (driven? cfg)
    (log/info :claude/title-side-call
              :provider "claude"
              :found false
              :note "no CLI switch; --no-session-persistence still emits ai-title")))

(defn- flag-args [streaming? driven?]
  ;; `--tools ""` disables ALL built-in tools (the real CLI flag; the prior
  ;; `--disallowed-tools all` denied a tool literally named "all" — i.e. nothing,
  ;; and warned). With no tools there is no tool loop, so the --print run is a
  ;; pure completion; `--max-turns` does not exist in this CLI version. Isaac
  ;; owns the transcript, so session persistence stays off. (isaac-kn7y)
  ;; Driven turns (isaac-5xn7) add stream-json input + MCP config so Claude Code
  ;; owns the tool loop against isaac's mcp-bridge.
  (cond-> ["--print"
           "--output-format" (if (or streaming? driven?) "stream-json" "json")]
    (or streaming? driven?) (conj "--include-partial-messages" "--verbose")
    driven? (into ["--input-format" "stream-json"
                   "--strict-mcp-config"
                   "--permission-mode" "bypassPermissions"])
    :always (into ["--tools" ""
                   "--no-session-persistence"])))

(defn- extra-args [cfg]
  (vec (or (:extra-args cfg) (:extraArgs cfg) [])))

(defn- command-path [cfg]
  (or (:command cfg) "claude"))

(defn- subprocess-env []
  (dissoc (into {} (System/getenv)) "ANTHROPIC_API_KEY"))

(defn- build-argv [cfg request streaming?]
  (let [driven  (driven? cfg)
        system  (build-system-prompt request)
        base    (into [(command-path cfg)]
                      (concat (extra-args cfg)
                              (flag-args streaming? driven)
                              ["--model" (:model request)]))]
    (if (seq (str/trim system))
      (into base ["--system-prompt" system])
      base)))

(defn- capture-stdin! [in]
  (reset! stdin-messages*
          (vec (keep parse-stdin-line (str/split-lines (or in ""))))))

(defn- argv->arg-map [argv]
  (loop [args (vec argv)
         m    {}]
    (if (empty? args)
      m
      (let [a (first args)]
        (if (str/starts-with? (str a) "--")
          (let [next       (second args)
                has-value? (and next (not (str/starts-with? (str next) "--")))]
            (recur (drop (if has-value? 2 1) args)
                   (if has-value?
                     (assoc m a next)
                     (assoc m a ""))))
          (recur (rest args) m))))))

(defn- cycle-rows [script n]
  (let [rows (filterv #(= n (:cycle %)) script)]
    (if (seq rows)
      rows
      (filterv #(= 1 (:cycle %)) script))))

(defn- parse-payload [kind payload]
  (if (and (#{"tool_use" "usage"} kind)
           (string? payload)
           (str/starts-with? (str/trim payload) "{"))
    (try (json/parse-string payload true)
         (catch Exception _ payload))
    payload))

(defn- cycle-events [rows]
  (let [text-deltas  (mapv :payload (filter #(= "text_delta" (:kind %)) rows))
        error-result (some #(when (= "error_result" (:kind %)) (:payload %)) rows)
        text         (some #(when (= "text" (:kind %)) (:payload %)) rows)
        thinking     (keep #(when (= "thinking" (:kind %)) (:payload %)) rows)
        tools        (keep #(when (= "tool_use" (:kind %))
                              (parse-payload "tool_use" (:payload %)))
                           rows)
        usage        (some #(when (= "usage" (:kind %))
                              (parse-payload "usage" (:payload %)))
                           rows)]
    (cond
      error-result
      [{:type     "result"
        :is_error true
        :result   error-result
        :usage    (or usage {})}]

      (seq text-deltas)
      (let [joined (apply str text-deltas)]
        (concat
          (map (fn [t]
                 {:type  "stream_event"
                  :event {:type  "content_block_delta"
                          :delta {:type "text_delta" :text t}}})
               text-deltas)
          [{:type    "message"
            :message {:role    "assistant"
                      :content [{:type "text" :text joined}]}}]
          [{:type        "result"
            :is_error    false
            :stop_reason "end_turn"
            :result      joined
            :usage       (or usage {})}]))

      :else
      (concat
        (map (fn [t]
               {:type  "content_block_delta"
                :delta {:type "thinking_delta" :thinking t}})
             thinking)
        (map (fn [p]
               {:type    "assistant"
                :message {:content [{:type  "tool_use"
                                     :id    (or (:id p) (str (java.util.UUID/randomUUID)))
                                     :name  (:name p)
                                     :input (or (:input p) (:arguments p) {})}]}})
             tools)
        (when text
          [{:type    "assistant"
            :message {:content [{:type "text" :text text}]}}])
        [{:type   "result"
          :result (or text "")
          :usage  (or usage {})}]))))

(defn- stream-json-without-verbose? [arg-map]
  (and (= "stream-json" (get arg-map "--output-format"))
       (not (contains? arg-map "--verbose"))))

(defn- simulate-fake-cli! [argv in]
  (let [script    (or @fake-cli* [])
        n         (swap! fake-cycle* inc)
        rows      (cycle-rows script n)
        arg-map   (argv->arg-map argv)
        json-out? (= "json" (get arg-map "--output-format"))
        text      (or (some #(when (= "text" (:kind %)) (:payload %)) rows) "")]
    (capture-stdin! in)
    (cond
      (stream-json-without-verbose? arg-map)
      {:exit 1 :out "" :err STREAM-JSON-NEEDS-VERBOSE}

      (and (not json-out?) @exit-before-stream*)
      (let [{:keys [exit err]} @exit-before-stream*]
        {:exit (or exit 1) :out "" :err (or err "")})

      json-out?
      {:exit 0
       :out  (json/generate-string {:type "result" :result text})
       :err  ""}

      :else
      {:exit 0
       :out  (str/join "\n" (map json/generate-string (cycle-events rows)))
       :err  ""})))

(defn- terminate-cli! []
  (when-not @terminated?*
    (when-let [p @live-process*]
      (try
        (let [proc (or (:proc p) p)]
          (when (instance? java.lang.Process proc)
            (.destroy proc)))
        (catch Exception _)))
    (reset! terminated?* true)
    (reset! live-process* nil)
    (log/info :claude/driver-terminated :provider "claude")))

(defn- install-cancel-hook! [cfg]
  (when-let [session-key (:session-key cfg)]
    (bridge-cancel/on-cancel! session-key terminate-cli!)))

(defn- run-process! [argv env in]
  (cond
    @stub-state*
    (let [stub @stub-state*]
      (stub {:argv argv :env env :in in}))

    @fake-cli*
    (simulate-fake-cli! argv in)

    :else
    (let [p (process/process argv {:env env :err :string :out :string :in (or in "")})]
      (reset! live-process* p)
      (let [result @p]
        (reset! live-process* nil)
        {:exit (:exit result)
         :out  (:out result)
         :err  (:err result)}))))

(defn- request-stdin [cfg request]
  (if (driven? cfg)
    (conversation->stream-json request)
    (conversation->prompt-text request)))

(declare claude-loop-driver)

(defn- stream-json-event [line]
  (when (seq (str/trim line))
    (try
      (json/parse-string line true)
      (catch Exception _ nil))))

(defn- parse-stream-events [out]
  (->> (str/split-lines (or out ""))
       (keep stream-json-event)
       vec))

(defn- unwrap-stream-event [event]
  (if (and (= "stream_event" (:type event)) (map? (:event event)))
    (assoc (:event event) :_envelope event)
    event))

(defn- event-type-counts [events]
  (->> events
       (keep :type)
       (map str)
       frequencies))

(defn- stderr-head [err]
  (let [s (str/trim (or err ""))]
    (if (> (count s) 500)
      (subs s 0 500)
      s)))

(defn- result-event? [event]
  (= "result" (:type event)))

(defn- result-error? [event]
  (boolean (or (true? (:is_error event))
               (= true (:isError event)))))

(defn- log-driver-exit! [result events]
  (let [result-evt (last (filter result-event? events))]
    (log/info :claude/driver-exit
              :provider "claude"
              :exit-code (or (:exit result) 0)
              :result-event (boolean result-evt)
              :stderr (not-empty (stderr-head (:err result)))
              :events (event-type-counts events))))

(defn- ensure-driver! [cfg]
  (when (:drives-tool-loop? cfg)
    (tool-loop/install-provider-driver! claude-loop-driver)))

(defn- fence-retry! [cfg request]
  (let [argv   (build-argv cfg request false)
        prompt (request-stdin cfg request)
        env    (subprocess-env)]
    (record-invocation! {:argv argv :env env :in prompt})
    (run-process! argv env prompt)))

(defn- cli-start-failed? [result]
  (and (not (zero? (:exit result)))
       (str/blank? (:out result))))

(defn- cli-error? [result]
  (let [events (parse-stream-events (:out result))
        result-evt (last (filter result-event? events))]
    (or (and result-evt (result-error? result-evt))
        (nil? result-evt))))

(defn- fence-fallback! [cfg request result reason]
  (when (compare-and-set! fallback-logged?* false true)
    (log/info :claude/driver-fallback
              :provider "claude"
              :reason reason
              :stderr (let [events (parse-stream-events (:out result))
                            result-evt (last (filter result-event? events))]
                        (or (not-empty (stderr-head (:err result)))
                            (when result-evt (str (:result result-evt)))
                            ""))))
  (reset! fail-mcp-init?* true)
  (reset! exit-before-stream* {:exit (:exit result) :err (:err result)})
  (fence-retry! cfg request))

(defn- invoke! [cfg request streaming?]
  (ensure-driver! cfg)
  (let [streaming? (and streaming? (not @fail-mcp-init?*) (not @exit-before-stream*))
        argv       (build-argv cfg request streaming?)
        prompt     (request-stdin cfg request)
        env        (subprocess-env)]
    (maybe-log-fallback! cfg)
    (log-title-side-call! cfg)
    (install-cancel-hook! cfg)
    (capture-stdin! prompt)
    (record-invocation! {:argv argv :env env :in prompt})
    (let [result (run-process! argv env prompt)
          events (parse-stream-events (:out result))]
      (when (and (:drives-tool-loop? cfg) (not @fail-mcp-init?*))
        (log-driver-exit! result events))
      (cond
        (and (:drives-tool-loop? cfg)
             (not @fail-mcp-init?*)
             (cli-start-failed? result))
        (fence-fallback! cfg request result :cli-start-failed)

        (and (:drives-tool-loop? cfg)
             (not @fail-mcp-init?*)
             (cli-error? result))
        (fence-fallback! cfg request result :cli-error)

        :else result))))

(defn- stream-json-delta-text [event]
  (let [event (unwrap-stream-event event)
        delta (:delta event)]
    (cond
      (and (map? delta)
           (or (nil? (:type delta))
               (= "text_delta" (:type delta)))
           (string? (:text delta)))
      (:text delta)

      (and (get-in event [:delta :type])
           (= "thinking_delta" (get-in event [:delta :type])))
      nil

      (get-in event [:content_block_delta :delta :text])
      (get-in event [:content_block_delta :delta :text])

      (string? (:text event))
      (:text event)

      :else nil)))

(defn- stream-json-delta-thinking [event]
  (let [event (unwrap-stream-event event)]
    (or (when (= "thinking_delta" (get-in event [:delta :type]))
          (get-in event [:delta :thinking]))
        (get-in event [:delta :thinking])
        (get-in event [:content_block_delta :delta :thinking]))))

(defn- content-tool-calls [content]
  (when (vector? content)
    (->> content
         (filter #(= "tool_use" (:type %)))
         (mapv (fn [b]
                 {:id        (or (:id b) (str (java.util.UUID/randomUUID)))
                  :name      (:name b)
                  :arguments (or (:input b) (:arguments b) {})
                  :raw       b})))))

(defn- parse-stream-json-output [lines]
  (loop [deltas    []
         reasoning []
         usage     (zero-usage)
         [line & more] lines]
    (if line
      (let [event (stream-json-event line)]
        (recur (if-let [t (when event (stream-json-delta-text event))]
                 (conj deltas t)
                 deltas)
               (if-let [t (when event (stream-json-delta-thinking event))]
                 (conj reasoning t)
                 reasoning)
               (if (and event (result-event? event))
                 (normalize-usage (:usage event))
                 usage)
               more))
      {:deltas deltas :reasoning reasoning :usage usage})))

(defn- parse-stream-json-response [out model]
  (let [events     (parse-stream-events out)
        tool-calls (atom [])
        texts      (atom [])
        reasoning  (atom [])
        usage      (atom (zero-usage))
        saw-delta? (atom false)]
    (doseq [event events]
      (when-let [t (stream-json-delta-thinking event)]
        (swap! reasoning conj t))
      (when-let [t (stream-json-delta-text event)]
        (reset! saw-delta? true)
        (swap! texts conj t))
      (when-let [content (get-in (unwrap-stream-event event) [:message :content])]
        (when (vector? content)
          (doseq [b content]
            (case (:type b)
              "tool_use" (swap! tool-calls conj {:id        (or (:id b) (str (java.util.UUID/randomUUID)))
                                                 :name      (:name b)
                                                 :arguments (or (:input b) (:arguments b) {})
                                                 :raw       b})
              "text" (when (and (not @saw-delta?) (:text b))
                       (swap! texts conj (:text b)))
              nil))))
      (when-let [tcs (content-tool-calls (get-in event [:content]))]
        (swap! tool-calls into tcs))
      (when (result-event? event)
        (reset! usage (normalize-usage (:usage event)))
        (when (and (empty? @texts)
                   (not (result-error? event))
                   (seq (str (:result event))))
          (swap! texts conj (str (:result event))))))
    (let [text  (str/join @texts)
          tcs   @tool-calls
          think (str/join @reasoning)]
      (cond-> {:message    (cond-> {:role "assistant" :content text}
                             (seq tcs) (assoc :tool_calls tcs))
               :model      model
               :tool-calls tcs
               :usage      @usage}
        (seq think) (assoc :reasoning {:summary think})))))

;; endregion ^^^^^ CLI Invocation ^^^^^

;; region ----- LoopDriver -----

(defn- prompt-tokens [usage]
  (+ (or (:input-tokens usage) 0)
     (or (:cache-read usage) 0)
     (or (:cache-write usage) 0)))

(defn claude-loop-driver
  "Provider-driven tool loop for claude-cli. Chat-fn is invoked per cycle
   (one CLI spawn per cycle under the stub; production still owns one
   conceptual turn). Token-counts accumulate provider-prompt tokens
   (input + cache_read + cache_creation) into :input-tokens so
   last-input-tokens/turn-input-tokens match isaac-vuto decision 5.
   Cache fields stay on the map because store-response!/extract-tokens
   re-read :input-tokens as raw input and add cache separately."
  [chat-fn followup-fn request tool-fn {:keys [max-loops cancelled? on-cycle]
                                        :or   {max-loops  tool-loop/default-max-loops
                                               cancelled? (constantly false)}}]
  (loop [req          request
         all-tools    []
         token-counts {:input-tokens 0 :output-tokens 0 :cache-read 0 :cache-write 0}
         loops        0]
    (if (cancelled?)
      (do
        (terminate-cli!)
        {:response     nil
         :tool-calls   all-tools
         :token-counts token-counts
         :cancelled?   true})
      (let [cycle-n  (inc loops)
            _        (when on-cycle (on-cycle :start cycle-n req))
            response (chat-fn req)]
        (if (or (:error response) (:unavailable? response))
          response
          (let [tool-calls (or (:tool-calls response)
                               (get-in response [:message :tool_calls])
                               [])
                usage      (or (:usage response) (zero-usage))
                new-tokens {:input-tokens  (+ (:input-tokens token-counts)
                                              (prompt-tokens usage))
                            :output-tokens (+ (:output-tokens token-counts)
                                              (or (:output-tokens usage) 0))
                            :cache-read    (+ (:cache-read token-counts)
                                              (or (:cache-read usage) 0))
                            :cache-write   (+ (:cache-write token-counts)
                                              (or (:cache-write usage) 0))}]
            (when on-cycle (on-cycle :end cycle-n response))
            (if (and (seq tool-calls) (< loops max-loops))
              (if (cancelled?)
                (do
                  (terminate-cli!)
                  {:response     nil
                   :tool-calls   (into all-tools tool-calls)
                   :token-counts new-tokens
                   :cancelled?   true})
                (let [results      (mapv (fn [tc]
                                           (tool-fn (:name tc) (or (:arguments tc) {})))
                                         tool-calls)
                      new-messages (followup-fn req response tool-calls results)]
                  (if (cancelled?)
                    (do
                      (terminate-cli!)
                      {:response     nil
                       :tool-calls   (into all-tools tool-calls)
                       :token-counts new-tokens
                       :cancelled?   true})
                    (recur (assoc req :messages new-messages)
                           (into all-tools tool-calls)
                           new-tokens
                           (inc loops)))))
              {:response     response
               :tool-calls   all-tools
               :token-counts new-tokens})))))))

;; endregion ^^^^^ LoopDriver ^^^^^

;; region ----- Public API -----

(defn chat [request _provider-name cfg]
  (ensure-driver! cfg)
  (let [result (invoke! cfg request false)]
    (if (failed? result)
      (error-response result)
      (if (driven? cfg)
        (parse-stream-json-response (:out result) (:model request))
        (let [{:keys [text usage]} (parse-json-output (:out result))]
          (success-response (:model request) text usage))))))

(defn chat-stream [request on-chunk _provider-name cfg]
  (ensure-driver! cfg)
  (let [result (invoke! cfg request (not @fail-mcp-init?*))
        fence? (or @fail-mcp-init?* @exit-before-stream*)]
    (if-not (failed? result)
      (if fence?
        (let [{:keys [text usage]} (parse-json-output (:out result))
              response (success-response (:model request) text usage)]
          (when (seq text)
            (on-chunk {:message {:role "assistant" :content text} :done false}))
          (on-chunk {:done true})
          response)
        (let [lines   (str/split-lines (:out result))
              {:keys [deltas reasoning usage]} (parse-stream-json-output lines)
              parsed  (parse-stream-json-response (:out result) (:model request))
              content (or (not-empty (str/join deltas))
                          (get-in parsed [:message :content])
                          "")]
          (doseq [think reasoning]
            (when (seq think)
              (on-chunk {:reasoning think})))
          (doseq [delta deltas]
            (when (seq delta)
              (on-chunk {:message {:role "assistant" :content delta} :done false})))
          (let [response (if (seq (:tool-calls parsed))
                           parsed
                           (success-response (:model request) content usage))]
            (on-chunk {:done true})
            (cond-> response
              (seq reasoning) (assoc :reasoning {:summary (str/join reasoning)})))))
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
  (config [_]
    ;; Grover's feature fixture clears the global driver atom after make.
    ;; Reinstall at the moment tool-loop/run asks whether we own the loop.
    (ensure-driver! cfg)
    cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build opts)))

(defn make [name cfg]
  (let [cfg (driven-cfg cfg)]
    (ensure-driver! cfg)
    (->ClaudeCliAPI name cfg)))

;; endregion ^^^^^ Public API ^^^^^
