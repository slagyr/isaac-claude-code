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

(defn- driven? [cfg]
  (boolean (and (:drives-tool-loop? cfg) (not @fail-mcp-init?*))))

(defn- maybe-log-fallback! [cfg]
  (when (and (or (:drives-tool-loop? cfg) @fail-mcp-init?*)
             (compare-and-set! fallback-logged?* false true))
    (when @fail-mcp-init?*
      (log/info :claude/driver-fallback :provider "claude" :reason :mcp-init))))

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
    (or streaming? driven?) (conj "--include-partial-messages")
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
  (let [text     (some #(when (= "text" (:kind %)) (:payload %)) rows)
        thinking (keep #(when (= "thinking" (:kind %)) (:payload %)) rows)
        tools    (keep #(when (= "tool_use" (:kind %))
                          (parse-payload "tool_use" (:payload %)))
                       rows)
        usage    (some #(when (= "usage" (:kind %))
                          (parse-payload "usage" (:payload %)))
                       rows)]
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
        :usage  (or usage {})}])))

(defn- simulate-fake-cli! [argv in]
  (let [script    (or @fake-cli* [])
        n         (swap! fake-cycle* inc)
        rows      (cycle-rows script n)
        json-out? (= "json" (get (argv->arg-map argv) "--output-format"))
        text      (or (some #(when (= "text" (:kind %)) (:payload %)) rows) "")]
    (capture-stdin! in)
    (if json-out?
      {:exit 0
       :out  (json/generate-string {:type "result" :result text})
       :err  ""}
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

(defn- ensure-driver! [cfg]
  (when (:drives-tool-loop? cfg)
    (tool-loop/install-provider-driver! claude-loop-driver)))

(defn- invoke! [cfg request streaming?]
  (ensure-driver! cfg)
  (let [streaming? (and streaming? (not @fail-mcp-init?*))
        argv       (build-argv cfg request streaming?)
        prompt     (request-stdin cfg request)
        env        (subprocess-env)]
    (maybe-log-fallback! cfg)
    (log-title-side-call! cfg)
    (install-cancel-hook! cfg)
    (capture-stdin! prompt)
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

    (and (get-in event [:delta :type])
         (= "thinking_delta" (get-in event [:delta :type])))
    nil

    (get-in event [:content_block_delta :delta :text])
    (get-in event [:content_block_delta :delta :text])

    (get-in event [:message :content 0 :text])
    (get-in event [:message :content 0 :text])

    (string? (:text event))
    (:text event)

    :else nil))

(defn- stream-json-delta-thinking [event]
  (or (when (= "thinking_delta" (get-in event [:delta :type]))
        (get-in event [:delta :thinking]))
      (get-in event [:delta :thinking])
      (get-in event [:content_block_delta :delta :thinking])))

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
               (if (= "result" (:type event))
                 (normalize-usage (:usage event))
                 usage)
               more))
      {:deltas deltas :reasoning reasoning :usage usage})))

(defn- parse-stream-json-response [out model]
  (let [lines      (str/split-lines (or out ""))
        tool-calls (atom [])
        texts      (atom [])
        reasoning  (atom [])
        usage      (atom (zero-usage))]
    (doseq [line lines]
      (when-let [event (stream-json-event line)]
        (when-let [t (stream-json-delta-thinking event)]
          (swap! reasoning conj t))
        (when-let [content (get-in event [:message :content])]
          (when (vector? content)
            (doseq [b content]
              (case (:type b)
                "text" (when (:text b) (swap! texts conj (:text b)))
                "tool_use" (swap! tool-calls conj {:id        (or (:id b) (str (java.util.UUID/randomUUID)))
                                                   :name      (:name b)
                                                   :arguments (or (:input b) (:arguments b) {})
                                                   :raw       b})
                nil))))
        (when-let [t (and (not (get-in event [:message :content]))
                          (stream-json-delta-text event))]
          (swap! texts conj t))
        (when-let [tcs (content-tool-calls (get-in event [:content]))]
          (swap! tool-calls into tcs))
        (when (= "result" (:type event))
          (reset! usage (normalize-usage (:usage event)))
          (when (and (empty? @texts) (seq (str (:result event))))
            (swap! texts conj (str (:result event)))))))
    (let [text (str/join @texts)
          tcs  @tool-calls
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
  (maybe-log-fallback! cfg)
  (let [driven (driven? cfg)
        result (invoke! cfg request false)]
    (if (failed? result)
      (error-response result)
      (if driven
        (parse-stream-json-response (:out result) (:model request))
        (let [{:keys [text usage]} (parse-json-output (:out result))]
          (success-response (:model request) text usage))))))

(defn chat-stream [request on-chunk _provider-name cfg]
  (ensure-driver! cfg)
  (maybe-log-fallback! cfg)
  (let [fence? @fail-mcp-init?*
        result (invoke! cfg request (not fence?))]
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
