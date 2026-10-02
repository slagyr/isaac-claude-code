(ns isaac.provider.claude-code.api.claude-cli
  (:require
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.llm.followup :as followup]
    [isaac.agent.llm.prompt.builder :as prompt]
    [isaac.agent.llm.tool-loop :as tool-loop]
    [isaac.agent.mcp.turns :as mcp-turns]
    [isaac.foundation.config.env :as env]
    [isaac.foundation.logger :as log]
    [isaac.provider.claude-code.mcp-listener :as mcp-listener]))

;; region ----- Test Hooks -----

(defonce ^:private stub-state* (atom nil))
(defonce ^:private invocations* (atom []))
;; Starts clear so the first real provider build can warn. Spec hooks set it
;; so make stays quiet; feature stubs clear it again.
(defonce ^:private oauth-token-warned?* (atom false))

(defn- record-invocation! [invocation]
  (swap! invocations* conj invocation))

(defn- attach-out! [_invocation out]
  (swap! invocations*
         (fn [invs]
           (if (empty? invs)
             invs
             (assoc invs (dec (count invs)) (assoc (peek invs) :out out))))))

(defn set-stub! [f]
  (reset! stub-state* f))

(defn clear-stub! []
  (reset! stub-state* nil)
  (reset! oauth-token-warned?* true))

(defn reset-oauth-token-warning! []
  (reset! oauth-token-warned?* false))

(defn clear-invocations! []
  (reset! invocations* []))

(defn invocations []
  (vec @invocations*))

(defonce ^:private fake-cli* (atom nil))
(defonce ^:private fake-cycle* (atom 0))
(defonce ^:private fake-mcp-failure?* (atom false))
(defonce ^:private exit-before-stream* (atom nil))
(defonce ^:private live-process* (atom nil))
(defonce ^:private terminated?* (atom false))
(defonce ^:private stdin-messages* (atom []))
(defonce ^:private last-cfg* (atom {}))
(defonce ^:private last-mcp-config* (atom nil))
(defonce ^:private drive-tool-fn* (atom nil))
(defonce ^:private on-driven-tool-cycle* (atom nil))
(defonce ^:private tools-in-result-only?* (atom false))

(def ^:private remembered-keys
  [:command :extra-args :extraArgs
   :stream-non-tool-turns :streamNonToolTurns
   :stream-supports-tool-calls :streamSupportsToolCalls
   :api :auth :env :forward-env :forwardEnv])

(defn- full-cfg? [cfg]
  (or (contains? cfg :command)
      (contains? cfg :api)
      (contains? cfg :extra-args)
      (contains? cfg :extraArgs)
      (contains? cfg :stream-non-tool-turns)
      (contains? cfg :streamNonToolTurns)))

(defn- remember-config! [cfg]
  ;; augment-provider remakes the Api with only session keys, dropping
  ;; :command, :extra-args, and :stream-non-tool-turns.
  ;; Full configs update the memory; session-only remakes reuse it.
  (when (full-cfg? cfg)
    (reset! last-cfg* (select-keys cfg remembered-keys))))

(defn- remembered-config [cfg]
  (remember-config! cfg)
  (merge @last-cfg* cfg))

(defn clear-fake-cli! []
  (reset! fake-cli* nil)
  (reset! fake-cycle* 0)
  (reset! fake-mcp-failure?* false)
  (reset! exit-before-stream* nil)
  (reset! live-process* nil)
  (reset! terminated?* false)
  (reset! stdin-messages* [])
  (reset! last-cfg* {})
  (reset! last-mcp-config* nil)
  (reset! drive-tool-fn* nil)
  (reset! on-driven-tool-cycle* nil)
  (reset! tools-in-result-only?* false)
  (reset! oauth-token-warned?* true))

(defn report-tools-in-result-only!
  "Make the fake CLI withhold its live per-tool-call cycle hook, as the real
   CLI does — production has no simulator, so the driver always takes the
   replay path there (isaac-8cur)."
  []
  (reset! tools-in-result-only?* true))

(defn simulate-mcp-init-failure! []
  (reset! fake-mcp-failure?* true))

(defn exit-before-streaming! [exit-code stderr]
  (reset! exit-before-stream* {:exit (or exit-code 1) :err (or stderr "")})
  (reset! fake-mcp-failure?* false))

(defn fake-cli-terminated? []
  @terminated?*)

(defn- content-as-text [content]
  (cond
    (string? content) content
    (vector? content) (->> content (filter #(= "text" (:type %))) (map :text) (str/join))
    :else (str content)))

(defn- parse-stdin-line [line]
  (let [t (str/trim (or line ""))]
    (when (seq t)
      (or (try
            (let [parsed  (json/parse-string t true)
                  msg     (or (:message parsed) parsed)
                  role    (or (:role msg) (:role parsed))
                  content (or (:content msg) (:content parsed))
                  text    (content-as-text content)]
              (when role
                (cond-> {:role (name role) :content text}
                  (:type parsed) (assoc :type (name (:type parsed)))
                  (:message parsed) (assoc :message {:role    (name (or (:role (:message parsed)) role))
                                                     :content text}))))
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
  (reset! terminated?* false)
  (reset! last-mcp-config* nil)
  (reset! fake-mcp-failure?* false))

(defn last-mcp-config []
  @last-mcp-config*)

;; endregion ^^^^^ Test Hooks ^^^^^

;; region ----- Prompt / Response -----

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

(defn- build-system-prompt
  "Tools are native MCP; never teach a textual tool-call protocol."
  [request]
  (str/join "\n\n"
            (remove str/blank?
                    [(system-text request)
                     (tool-defs-text request)])))

(defn- conversation->prompt-text [request]
  (let [role-lines (for [{:keys [role content]} (conversation-messages request)]
                     (str (str/capitalize (name role)) ": " (content->text content)))]
    (str/join "\n\n" (remove str/blank? role-lines))))

(defn- conversation->stream-json [request]
  ;; stream-json input accepts only user envelopes. Prior turns become prose
  ;; inside a user message (assistant history as "Assistant: …"); the live
  ;; prompt is the last envelope. (isaac-6z4r)
  (let [msgs (vec (conversation-messages request))]
    (if (empty? msgs)
      ""
      (let [history (butlast msgs)
            live    (last msgs)
            hist-text (->> history
                           (map (fn [{:keys [role content]}]
                                  (str (str/capitalize (name role)) ": " (content->text content))))
                           (str/join "\n\n"))
            envelopes (cond-> []
                        (seq hist-text)
                        (conj {:type "user" :message {:role "user" :content hist-text}})

                        :always
                        (conj {:type    "user"
                               :message {:role    "user"
                                         :content (content->text (:content live))}}))]
        (->> envelopes
             (map json/generate-string)
             (str/join "\n"))))))

(defn- zero-usage []
  {:prompt-tokens 0 :output-tokens 0})

(defn- parse-cli-usage [usage]
  (when (and (map? usage)
             (or (contains? usage :input_tokens)
                 (contains? usage :output_tokens)
                 (contains? usage :cache_read_input_tokens)
                 (contains? usage :cache_creation_input_tokens)))
    (let [cache-read  (or (:cache_read_input_tokens usage) 0)
          cache-write (or (:cache_creation_input_tokens usage) 0)]
      {:prompt-tokens      (+ (or (:input_tokens usage) 0) cache-read cache-write)
       :output-tokens      (or (:output_tokens usage) 0)
       :cache-read-tokens  cache-read
       :cache-write-tokens cache-write})))

(defn- normalize-usage [usage]
  (cond
    (not (map? usage)) (zero-usage)
    (contains? usage :prompt-tokens) usage
    :else (or (parse-cli-usage usage) (zero-usage))))

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

(def ^:private limit-failure-re
  "The CLI's own words for a seat that has run out of window: the subscription
   session limit, an API usage limit, or a plain rate limit."
  #"(?i)session limit|usage limit|usage_limit_reached|rate[ _]limit|too many requests|credit balance|quota exceeded")

(def ^:private cli-auth-failure-re
  "Login trouble the CLI reports for itself. Narrower than `auth-failure-re`:
   this one is matched only against stderr and the result event's error text,
   never the transcript (isaac-benp)."
  #"(?i)failed to authenticate|oauth (?:session |token )?expired|not logged in|please run\s*/login|invalid api key|not authenticated|no credentials")

;; endregion ^^^^^ Prompt / Response ^^^^^

;; region ----- CLI Invocation -----

(def ^:private STREAM-JSON-NEEDS-VERBOSE
  "Error: When using --print, --output-format=stream-json requires --verbose")

(defn- driven? [request]
  (boolean (seq (:tools request))))

(defn- log-title-side-call! []
  ;; khgy: Claude Code has no documented flag to skip the per-invocation
  ;; title-generation side call. --no-session-persistence still writes an
  ;; ai-title stub (anthropics/claude-code#49565, #52555). Accept the cost
  ;; and log it so operators can see it per driven turn.
  (log/info :claude/title-side-call
            :provider "claude"
            :found false
            :note "no CLI switch; --no-session-persistence still emits ai-title"))

(defn- flag-args [streaming? driven? mcp-config-path]
  ;; `--tools ""` disables ALL built-in tools (the real CLI flag; the prior
  ;; `--disallowed-tools all` denied a tool literally named "all" — i.e. nothing,
  ;; and warned). With no tools there is no tool loop, so the --print run is a
  ;; pure completion; `--max-turns` does not exist in this CLI version. Isaac
  ;; owns the transcript, so session persistence stays off. (isaac-kn7y)
  ;; Driven turns (isaac-5xn7 / isaac-6z4r) add stream-json input + MCP config
  ;; so Claude Code owns the tool loop against isaac's per-turn listener
  ;; (isaac-mbnb: HTTP straight to the listener, no bridge process).
  (cond-> ["--print"
           "--output-format" (if (or streaming? driven?) "stream-json" "json")]
    (or streaming? driven?) (conj "--include-partial-messages" "--verbose")
    driven? (into ["--input-format" "stream-json"
                   "--strict-mcp-config"
                   "--permission-mode" "bypassPermissions"])
    (and driven? mcp-config-path) (into ["--mcp-config" mcp-config-path])
    :always (into ["--tools" ""
                   "--no-session-persistence"])))

(defn- extra-args [cfg]
  (vec (or (:extra-args cfg) (:extraArgs cfg) [])))

(defn- command-path [cfg]
  (or (:command cfg) "claude"))

(def ^:private oauth-token-env "CLAUDE_CODE_OAUTH_TOKEN")
(def ^:private default-forward-env [oauth-token-env])

(defn- config-env
  "A provider's :env as plain strings. EDN may hand us keyword keys."
  [cfg]
  (reduce-kv (fn [m k v]
               (assoc m (if (keyword? k) (name k) (str k)) (str v)))
             {}
             (or (:env cfg) {})))

(defn- env-name [name]
  (cond
    (keyword? name) (clojure.core/name name)
    (string? name)  name
    (nil? name)     nil
    :else           (str name)))

(defn- forward-names [cfg]
  (let [names (or (:forward-env cfg) (:forwardEnv cfg))]
    (if (nil? names) default-forward-env names)))

(defn- forwarded-env [cfg]
  (into {}
        (keep (fn [name]
                (let [k (env-name name)
                      v (when k (env/env k))]
                  (when (and k
                             (not= k "ANTHROPIC_API_KEY")
                             (string? v)
                             (not (str/blank? v)))
                    [k v]))))
        (forward-names cfg)))

(defn- subprocess-env
  "The server's environment, minus ANTHROPIC_API_KEY, plus named values from
   isaac.foundation.config.env/env (:forward-env; default CLAUDE_CODE_OAUTH_TOKEN). The
   provider's :env map is a literal overlay on top (CLAUDE_CONFIG_DIR for a
   second subscription, isaac-12fo). ANTHROPIC_API_KEY stays stripped after
   both merges. A missing or blank name is omitted (isaac-1awj)."
  [cfg]
  (-> (into {} (.environment (ProcessBuilder. [])))
      (merge (forwarded-env cfg))
      (merge (config-env cfg))
      (dissoc "ANTHROPIC_API_KEY")))

(defn- warn-missing-oauth-token! [provider-name cfg]
  (when (and (some #(= oauth-token-env (env-name %)) (forward-names cfg))
             (str/blank? (env/env oauth-token-env))
             (compare-and-set! oauth-token-warned?* false true))
    (log/warn :claude/oauth-token-missing
              :provider provider-name
              :name oauth-token-env
              :message "no token will be passed to claude")))

(defn- write-mcp-config!
  "The MCP config Claude Code reads: an HTTP server naming this turn's own
   loopback listener, with the per-turn nonce in the Authorization header —
   Claude Code's Streamable-HTTP MCP client talks to it directly, no stdio
   bridge process in between (isaac-mbnb)."
  [url nonce]
  (let [file (java.io.File/createTempFile "isaac-mcp-" ".json")
        body {:mcpServers {:isaac {:type    "http"
                                   :url     url
                                   :headers {:Authorization (str "Bearer " nonce)}}}}]
    (spit file (json/generate-string body))
    (reset! last-mcp-config* {:path (.getAbsolutePath file) :body body})
    (.getAbsolutePath file)))

(defn- isaac-tool-name [name]
  (let [s (str name)]
    (if (str/starts-with? s "mcp__isaac__")
      (subs s (count "mcp__isaac__"))
      s)))

(defn- register-mcp-turn! [cfg request]
  (let [turn-id (str (java.util.UUID/randomUUID))
        tools   (mapv (fn [t]
                        (let [fn* (or (:function t) t)]
                          {:name        (or (:name fn*) (:name t))
                           :description (or (:description fn*) "")
                           :parameters  (or (:parameters fn*) {:type "object"})}))
                      (or (:tools request) []))
        tool-fn (or @drive-tool-fn* (:tool-fn cfg) (fn [_name _args] ""))]
    (try
      (mcp-turns/register! turn-id {:session-key (:session-key cfg)
                                    :tool-fn     tool-fn
                                    :tools       tools})
      (let [{:keys [url nonce]} (mcp-listener/start! turn-id)]
        {:turn-id turn-id :nonce nonce :path (write-mcp-config! url nonce)})
      (catch Exception e
        (mcp-turns/clear! turn-id)
        (throw e)))))

(defn- cleanup-mcp-turn! [{:keys [turn-id]}]
  (when turn-id
    (try (mcp-listener/stop! turn-id) (catch Exception _))
    (try (mcp-turns/clear! turn-id) (catch Exception _))))

(defn- build-argv [cfg request streaming? mcp-config-path]
  (let [driven  (driven? request)
        system  (build-system-prompt request)
        base    (into [(command-path cfg)]
                      (concat (extra-args cfg)
                              (flag-args streaming? driven mcp-config-path)
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
  (if (and (#{"tool_use" "usage" "result_usage" "mcp_status"} kind)
           (string? payload)
           (str/starts-with? (str/trim payload) "{"))
    (try (json/parse-string payload true)
         (catch Exception _ payload))
    payload))

(defn- mcp-init-event [payload]
  (when payload
    {:type        "system"
     :subtype     "init"
     :mcp_servers (or (:mcp_servers payload) [])
     :tools       (or (:tools payload) [])}))

(defn- cycle-block-events [rows]
  (let [text-deltas  (mapv :payload (filter #(= "text_delta" (:kind %)) rows))
        error-result (some #(when (= "error_result" (:kind %)) (:payload %)) rows)
        text         (some #(when (= "text" (:kind %)) (:payload %)) rows)
        thinking     (keep #(when (= "thinking" (:kind %)) (:payload %)) rows)
        tools        (keep #(when (= "tool_use" (:kind %))
                              (parse-payload "tool_use" (:payload %)))
                           rows)
        usage        (some #(when (= "usage" (:kind %))
                              (parse-payload "usage" (:payload %)))
                           rows)
        result-usage (some #(when (= "result_usage" (:kind %))
                              (parse-payload "result_usage" (:payload %)))
                           rows)
        mcp-status   (some #(when (= "mcp_status" (:kind %))
                              (parse-payload "mcp_status" (:payload %)))
                           rows)
        result-text  (some #(when (= "result_text" (:kind %)) (:payload %)) rows)
        init         (mcp-init-event mcp-status)]
    (cond
      error-result
      {:init         init
       :body         []
       :result-event {:type     "result"
                      :is_error true
                      :result   error-result
                      :usage    (or usage {})}}

      (seq text-deltas)
      (let [joined (apply str text-deltas)]
        {:init         init
         :body         (concat
                         (map (fn [t]
                                {:type  "stream_event"
                                 :event {:type  "content_block_delta"
                                         :delta {:type "text_delta" :text t}}})
                              text-deltas)
                         [{:type    "message"
                           :message {:role    "assistant"
                                     :content [{:type "text" :text (or text joined)}]
                                     :usage   (or usage {})}}]
                         (when text
                           [{:type    "assistant"
                             :message {:role    "assistant"
                                       :content [{:type "text" :text text}]}}]))
         :result-event {:type        "result"
                        :is_error    false
                        :stop_reason "end_turn"
                        :result      (or result-text text joined)
                        :usage       (or result-usage usage {})}})

      :else
      (let [tool-blocks (mapv (fn [p]
                                {:type  "tool_use"
                                 :id    (or (:id p) (str (java.util.UUID/randomUUID)))
                                 :name  (:name p)
                                 :input (or (:input p) (:arguments p) {})})
                              tools)
            text-block  (when text {:type "text" :text text})
            content     (vec (concat (when (and text-block (seq tool-blocks)) [text-block])
                                     tool-blocks
                                     (when (and text-block (empty? tool-blocks)) [text-block])))]
        {:init         init
         :body         (concat
                         (map (fn [t]
                                {:type  "content_block_delta"
                                 :delta {:type "thinking_delta" :thinking t}})
                              thinking)
                         (when (seq content)
                           [{:type    "assistant"
                             :message {:content content :usage (or usage {})}}]))
         :result-event {:type   "result"
                        :result (or result-text text "")
                        :usage  (or result-usage usage {})}}))))

(defn- cycle-events [rows]
  (let [{:keys [init body result-event]} (cycle-block-events rows)]
    (concat (when init [init]) body [result-event])))

(defn- script-cycle-ns [script]
  (->> script (keep :cycle) distinct sort vec))

(defn- scripted-result-usage
  "The CLI's result usage is cumulative spend. A scripted result_usage row is
   that figure; otherwise it is the sum of the per-request usage rows."
  [script]
  (or (some #(when (= "result_usage" (:kind %))
               (parse-payload "result_usage" (:payload %)))
            script)
      (reduce (fn [total row]
                (if (= "usage" (:kind row))
                  (merge-with + total (parse-payload "usage" (:payload row)))
                  total))
              {}
              script)))

(defn- script-events
  "Emit each request's usage on its assistant message and the turn's spend on
   the single final result, as the CLI does for a multi-cycle turn."
  [script]
  (let [ns*          (or (not-empty (script-cycle-ns script)) [1])
        result-usage (scripted-result-usage script)]
    (mapcat
      (fn [n]
        (let [rows                             (cycle-rows script n)
              {:keys [init body result-event]} (cycle-block-events rows)]
          (concat (when (and init (= n (first ns*))) [init])
                  body
                  (when (= n (last ns*))
                    [(assoc result-event :usage result-usage)]))))
      ns*)))

(defn- event-tool-uses [evt]
  (filterv #(= "tool_use" (:type %))
           (or (get-in evt [:message :content]) [])))

(defn- result-shaped? [evt]
  (= "result" (:type evt)))

(defn- simulate-driven-script!
  "One-process fake: every scripted cycle, MCP tool_use dispatched through the
   drive tool-fn (the registry path), stop if the process was terminated."
  [script]
  (let [ns* (or (not-empty (script-cycle-ns script)) [1])]
    (loop [remaining ns*
           acc       []]
      (cond
        (or (empty? remaining) @terminated?*)
        (if (some result-shaped? acc)
          acc
          (concat acc [{:type "result" :result "" :usage {}}]))

        :else
        (let [n                                (first remaining)
              rows                             (cycle-rows script n)
              {:keys [init body result-event]} (cycle-block-events rows)
              last?                            (= n (last ns*))
              init*                            (when (and init (= n (first ns*))) [init])
              tcs                              (mapv (fn [b]
                                                       {:id        (or (:id b) (str (java.util.UUID/randomUUID)))
                                                        :name      (isaac-tool-name (:name b))
                                                        :arguments (or (:input b) (:arguments b) {})
                                                        :raw       b})
                                                     (mapcat event-tool-uses body))
              aside                            (some #(when (= "text" (:kind %)) (:payload %)) rows)
              hook                             @on-driven-tool-cycle*]
          (when (and hook (seq tcs) (not @tools-in-result-only?*))
            (hook :before aside tcs))
          (doseq [evt body
                  b   (event-tool-uses evt)]
            (when-let [f @drive-tool-fn*]
              (try
                (f (isaac-tool-name (:name b)) (or (:input b) (:arguments b) {}))
                (catch Exception _))))
          (when (and hook (seq tcs) (not @tools-in-result-only?*))
            (hook :after aside tcs))
          (let [tail (when (or last? @terminated?*)
                       [(if last?
                          (assoc result-event :usage (scripted-result-usage script))
                          {:type "result" :result "" :usage {}})])
                acc* (concat acc init* body tail)]
            (if (or last? @terminated?*)
              acc*
              (recur (rest remaining) acc*))))))))

(defn- stream-json-without-verbose? [arg-map]
  (and (= "stream-json" (get arg-map "--output-format"))
       (not (contains? arg-map "--verbose"))))

(defn- envelope-stdin-line? [line]
  (let [t (str/trim (or line ""))]
    (when (seq t)
      (try
        (let [parsed (json/parse-string t true)]
          (and (= "user" (str (:type parsed)))
               (map? (:message parsed))))
        (catch Exception _ false)))))

(defn- bare-stdin? [in]
  (let [lines (remove str/blank? (str/split-lines (or in "")))]
    (and (seq lines) (not (every? envelope-stdin-line? lines)))))

(defn- simulate-fake-cli! [argv in]
  (let [script      (or @fake-cli* [])
        n           (swap! fake-cycle* inc)
        rows        (cycle-rows script n)
        arg-map     (argv->arg-map argv)
        json-out?   (= "json" (get arg-map "--output-format"))
        mcp-config? (contains? arg-map "--mcp-config")
        text        (or (some #(when (= "text" (:kind %)) (:payload %)) rows) "")
        script*     (if (or json-out? mcp-config?)
                      script
                      (remove #(= "tool_use" (:kind %)) script))
        events      (if mcp-config?
                      (simulate-driven-script! script*)
                      (cycle-events (if (or json-out? mcp-config?)
                                      rows
                                      (remove #(= "tool_use" (:kind %)) rows))))]
    (capture-stdin! in)
    (cond
      (stream-json-without-verbose? arg-map)
      {:exit 1 :out "" :err STREAM-JSON-NEEDS-VERBOSE}

      (and mcp-config? @exit-before-stream*)
      (let [{:keys [exit err]} @exit-before-stream*]
        {:exit (or exit 1) :out "" :err (or err "")})

      (and (not json-out?) (bare-stdin? in))
      {:exit 0 :out "" :err ""}

      json-out?
      {:exit 0
       :out  (json/generate-string {:type "result" :result text})
       :err  ""}

      :else
      {:exit 0
       :out  (str/join "\n" (map json/generate-string events))
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
    (let [stub @stub-state*
          result (stub {:argv argv :env env :in in})]
      (attach-out! {:argv argv :env env :in in} (:out result))
      result)

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
  (if (driven? request)
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

(defn- init-event [events]
  (first (filter #(and (= "system" (str (:type %)))
                       (= "init" (str (:subtype %))))
                 events)))

(defn- mcp-tool-count [init]
  (let [tools (:tools init)]
    (cond
      (number? tools) tools
      (sequential? tools) (count tools)
      :else 0)))

(defn- isaac-http [init]
  (let [servers (or (:mcp_servers init) (:mcpServers init) [])]
    (first (filter #(= "isaac" (str (:name %))) servers))))

(defn- isaac-status [init]
  (str (:status (isaac-http init))))

(defn- isaac-failed? [init]
  (= "failed" (isaac-status init)))

(defn- log-mcp-status! [init]
  (when init
    (log/info :claude/mcp-status
              :provider "claude"
              :servers (or (:mcp_servers init) (:mcpServers init) [])
              :tools (mcp-tool-count init))))

(defn- mcp-failed? [init request]
  (and init
       (seq (:tools request))
       (or (isaac-failed? init)
           (and (= "connected" (isaac-status init))
                (zero? (mcp-tool-count init))))))

(defn- event-usage
  "The usage an event reports, or nil when it reports none. An empty usage map
   is silence, not zero: a cycle that says nothing must not overwrite what the
   cycle before it measured (isaac-ewxh)."
  [event]
  (let [u (or (:usage event)
              (get-in event [:message :usage])
              (get-in (unwrap-stream-event event) [:usage]))]
    (when (map? u)
      (if (contains? u :prompt-tokens) u (parse-cli-usage u)))))

(defn- stream-cycle-usages
  "Each request's usage, in stream order. The result event is the turn's
   spend, not another request, so it counts only when no message reported
   usage of its own (isaac-6ef2)."
  [events]
  (let [requests (vec (keep event-usage (remove result-event? events)))]
    (if (seq requests)
      requests
      (vec (keep event-usage (filter result-event? events))))))

(defn- sum-usages [usages]
  (reduce (fn [total usage]
            (merge-with + total (select-keys usage [:prompt-tokens :output-tokens
                                                    :cache-read-tokens :cache-write-tokens])))
          {:prompt-tokens 0 :output-tokens 0 :cache-read-tokens 0 :cache-write-tokens 0}
          usages))

(defn- log-driver-exit!
  "One line per driven turn, carrying what the turn cost. A turn whose last
   request died still ran its cycles, and the log is where that cost stays
   readable when the session file cannot be (isaac-ewxh)."
  [result events]
  (let [result-evt  (last (filter result-event? events))
        usages      (stream-cycle-usages events)
        sums        (sum-usages usages)
        cache-read  (:cache-read-tokens sums)
        cache-write (:cache-write-tokens sums)]
    (log/info :claude/driver-exit
              :provider "claude"
              :exit-code (or (:exit result) 0)
              :result-event (boolean result-evt)
              :stderr (not-empty (stderr-head (:err result)))
              :cycles (count usages)
              :input-tokens (- (:prompt-tokens sums) cache-read cache-write)
              :cache-read-tokens cache-read
              :cache-write-tokens cache-write
              :output-tokens (:output-tokens sums)
              :events (event-type-counts events))))

(defn- result-error-text
  "The CLI's own error signal: the text of a result event that reports
   is_error. Never the assistant or tool text in the stream (isaac-benp)."
  [events]
  (when-let [evt (last (filter result-event? events))]
    (when (result-error? evt)
      (not-empty (str/trim (str (:result evt)))))))

(defn- auth-signal
  "Stderr plus the result event's own error text. The transcript in stdout
   is not a login failure (isaac-benp)."
  [result]
  (str (:err result) "\n"
       (or (result-error-text (parse-stream-events (:out result))) "")))

(defn- auth-failure? [result]
  (boolean (re-find auth-failure-re (auth-signal result))))

(defn- error-message [result]
  (or (not-empty (str/trim (str (:err result))))
      (not-empty (str/trim (or (result-error-text (parse-stream-events (:out result))) "")))
      "claude binary failed"))

(defn- error-response
  "Loud on the prompt path (:error + :message) AND classified for hail
   defer+attention when the failure is auth ({:unavailable? true :reason :auth},
   the provider_wall convention). (isaac-kn7y). Auth is stderr or the result
   event's error text, never the transcript (isaac-benp)."
  [result]
  (cond-> {:error :llm-error :message (error-message result)}
    (auth-failure? result)
    (assoc :unavailable? true :reason :auth)))

(defn- failed?
  "A run failed when the process exited nonzero, the result event reports
   is_error, or stderr / that error text says the CLI is not logged in.
   A phrase in the transcript is not a failure (isaac-benp)."
  [result]
  (or (not (zero? (:exit result)))
      (result-error? (last (filter result-event? (parse-stream-events (:out result)))))
      (auth-failure? result)))

(defn- weather-kind
  "Provider weather the CLI reported for itself, in the agent's vocabulary
   (isaac.agent.drive.provider-wall): a limit is a wall, a login failure is auth."
  [text]
  (cond
    (re-find limit-failure-re text) {:error :rate-limited :reason :wall}
    (re-find cli-auth-failure-re text) {:error :auth-failed :reason :auth}))

(defn- cli-weather
  "Classify the CLI's own error signal as provider weather."
  [result events]
  (let [text (str/join "\n" (remove str/blank?
                                    [(str (:err result)) (result-error-text events)]))]
    (when-let [kind (weather-kind text)]
      (let [usages (stream-cycle-usages events)]
        (assoc kind
               :unavailable? true
               :message (stderr-head text)
               :usage (sum-usages usages)
               :cycle-usages usages)))))

(defn- cli-start-failed? [result]
  (and (not (zero? (:exit result)))
       (str/blank? (:out result))))

(defn- cli-error? [result]
  (let [events (parse-stream-events (:out result))
        result-evt (last (filter result-event? events))]
    (or (and result-evt (result-error? result-evt))
        (nil? result-evt))))

(defn- mcp-weather [result events]
  (let [usages (stream-cycle-usages events)]
    {:error          :mcp-unavailable
     :reason         :mcp-unavailable
     :unavailable?   true
     :message        (or (not-empty (stderr-head (:err result)))
                         (result-error-text events)
                         "Claude Code MCP unavailable")
     :usage          (sum-usages usages)
     :cycle-usages   usages}))

(defn- mcp-init-weather [exception]
  {:error        :mcp-unavailable
   :reason       :mcp-unavailable
   :unavailable? true
   :message      (.getMessage exception)})

(defn- invoke! [cfg request streaming?]
  (let [driven (driven? request)
        mcp    (try
                 (when driven (register-mcp-turn! cfg request))
                 (catch Exception e {:init-error e}))]
    (try
      (if-let [error (:init-error mcp)]
        {:weather (mcp-init-weather error)}
        (let [argv  (build-argv cfg request streaming? (:path mcp))
              input (request-stdin cfg request)
              env   (subprocess-env cfg)]
          (when driven (log-title-side-call!))
          (install-cancel-hook! cfg)
          (capture-stdin! input)
          (record-invocation! {:argv argv :env env :in input})
          (let [result  (if (and driven @fake-mcp-failure?*)
                          {:exit 1 :out "" :err "Claude Code MCP unavailable"}
                          (run-process! argv env input))
                events  (parse-stream-events (:out result))
                init    (init-event events)
                weather (cli-weather result events)]
            (when driven
              (when init (log-mcp-status! init))
              (log-driver-exit! result events))
            (cond
              weather (assoc result :weather weather)
              (and driven (or (cli-start-failed? result)
                              (cli-error? result)
                              (mcp-failed? init request)))
              (assoc result :weather (mcp-weather result events))
              :else result))))
      (catch Exception e
        (if driven
          {:weather (mcp-init-weather e)}
          (throw e)))
      (finally
        (when (:turn-id mcp) (cleanup-mcp-turn! mcp))))))

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

(defn- ->tool-call [b]
  {:id        (or (:id b) (str (java.util.UUID/randomUUID)))
   :name      (isaac-tool-name (:name b))
   :arguments (or (:input b) (:arguments b) {})
   :raw       b})

(defn- content-tool-calls [content]
  (when (vector? content)
    (->> content
         (filter #(= "tool_use" (:type %)))
         (mapv ->tool-call))))

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
  (let [events       (parse-stream-events out)
        tool-calls   (atom [])
        texts        (atom [])
        asides       (atom [])
        cycle-text   (atom [])
        reasoning    (atom [])
        saw-delta?   (atom false)
        result-text* (atom nil)
        flush-cycle! (fn [has-tools?]
                       (let [joined (str/join @cycle-text)]
                         (reset! cycle-text [])
                         (reset! saw-delta? false)
                         (when (seq joined)
                           (if has-tools?
                             (swap! asides conj joined)
                             (swap! texts conj joined)))))]
    (doseq [event events]
      (when-let [t (stream-json-delta-thinking event)]
        (swap! reasoning conj t))
      (when-let [t (stream-json-delta-text event)]
        (reset! saw-delta? true)
        (swap! cycle-text conj t))
      (when-let [content (get-in (unwrap-stream-event event) [:message :content])]
        (when (vector? content)
          (let [blocks     content
                has-tools? (boolean (some #(= "tool_use" (:type %)) blocks))]
            (doseq [b blocks]
              (case (:type b)
                "tool_use" (swap! tool-calls conj (->tool-call b))
                "text" (when (and (not @saw-delta?) (:text b))
                         (swap! cycle-text conj (:text b)))
                nil))
            (flush-cycle! has-tools?))))
      (when-let [tcs (content-tool-calls (get-in event [:content]))]
        (swap! tool-calls into tcs))
      (when (result-event? event)
        (flush-cycle! false)
        (when (and (not (result-error? event))
                   (seq (str (:result event))))
          (reset! result-text* (str (:result event))))))
    (flush-cycle! false)
    (let [text   (or (not-empty @result-text*) (str/join @texts) "")
          tcs    @tool-calls
          think  (str/join @reasoning)
          usages (stream-cycle-usages events)]
      (cond-> {:content      text
               :model        model
               :stop-reason  :end-turn
               :tool-calls   tcs
               :usage        (or (last usages) (zero-usage))
               :cycle-usages usages}
        (not (str/blank? think)) (assoc :reasoning {:summary think})
        (seq @asides) (assoc :asides @asides)))))

(defn- weather-response
  "The provider-error response for a run the CLI ended with weather. It carries
   the CLI's own message and no content — a `system`/`init` event is never
   assistant text (isaac-2sxf) — plus the usage of every cycle that finished
   before the wall (isaac-ewxh)."
  [weather model]
  (assoc weather :model model))

;; endregion ^^^^^ CLI Invocation ^^^^^

;; region ----- LoopDriver -----

(defn- add-usage [turn-usage request-usage]
  (-> (merge-with + turn-usage request-usage)
      (update :requests inc)))

(defn- empty-turn-usage []
  {:requests 0 :prompt-tokens 0 :output-tokens 0})

(defn- fire-on-cycle! [on-cycle phase n payload]
  (when on-cycle (on-cycle phase n payload)))

(defn- cycle-response [response tool-calls content]
  (-> response
      (dissoc :asides :cycle-usages)
      (assoc :content (or content (:content response) ""))
      (assoc :stop-reason (if (seq tool-calls) :tool-use :end-turn))
      (assoc :tool-calls tool-calls)))

(defn claude-loop-driver
  "Provider-driven tool loop for claude-cli. One CLI process per turn: chat-fn
   is invoked once and runs to the result event. MCP tools execute through the
   registry (isaac-zocg); the driver maps mcp__isaac__ names to isaac names and
   never re-dispatches through the drive's tool-fn. Returns the same normalized
   loop-result and turn-usage contract as the default tool loop."
  [chat-fn _followup-fn request tool-fn {:keys [cancelled? on-cycle]
                                         :or   {cancelled? (constantly false)}}]
  (reset! drive-tool-fn* tool-fn)
  (try
    (if (cancelled?)
      (do
        (terminate-cli!)
        {:response     nil
         :tool-calls   []
         :usage        (empty-turn-usage)
         :cancelled?   true})
      (let [tool-calls*        (atom [])
            cycle-n*           (atom 0)
            live-tool-cycles?* (atom false)
            fire-start!        (fn []
                                 (let [n (swap! cycle-n* inc)]
                                   (fire-on-cycle! on-cycle :start n request)
                                   n))]
        (reset! on-driven-tool-cycle*
                (fn [phase aside tcs]
                  (case phase
                    :before (when (seq tcs)
                              (reset! live-tool-cycles?* true)
                              (fire-on-cycle! on-cycle :end @cycle-n*
                                              (cycle-response {:content (or aside "")
                                                               :model (:model request)
                                                               :usage (zero-usage)}
                                                              tcs
                                                              aside)))
                    :after (when-not (cancelled?)
                             (fire-start!))
                    nil)))
        (fire-start!)
        (let [response (chat-fn request)]
          (if (or (:error response) (:unavailable? response))
            response
            (let [tool-calls (mapv (fn [tc]
                                     (assoc tc :name (isaac-tool-name (:name tc))))
                                   (:tool-calls response))
                  usage      (or (:usage response) (zero-usage))
                  usages     (not-empty (or (:cycle-usages response) []))
                  turn-usage (if usages
                               (reduce add-usage (empty-turn-usage) usages)
                               (add-usage (empty-turn-usage) usage))
                  cancelled  (boolean (cancelled?))]
              (reset! tool-calls* tool-calls)
              (if cancelled
                (do
                  (terminate-cli!)
                  {:response     nil
                   :tool-calls   tool-calls
                   :usage        turn-usage
                   :cancelled?   true})
                (let [asides (vec (or (:asides response) []))]
                  ;; Replayed tool cycles never met the model: the usage on `response`
                  ;; belongs to the turn's last cycle, not to each replayed one. Carry
                  ;; zero usage here, as the live `:before` path does, so only the final
                  ;; cycle stamps the session's prompt size (isaac-8cur).
                  (when-not @live-tool-cycles?*
                    (doseq [[i tc] (map-indexed vector tool-calls)]
                      (fire-on-cycle! on-cycle :end @cycle-n*
                                      (cycle-response (assoc response :usage (zero-usage))
                                                      [tc]
                                                      (get asides i)))
                      (fire-start!)))
                  (let [final (cycle-response response [] nil)
                        gauge (:prompt-tokens (first usages))]
                    (fire-on-cycle! on-cycle :end @cycle-n* final)
                    ;; The response keeps the last cycle. The gauge is the first
                    ;; request, which is the prompt the next Isaac turn repeats
                    ;; (isaac-6ef2). A missing or empty cycle list leaves the
                    ;; gauge unset so the agent keeps today's response stamp.
                    (cond-> {:response   final
                             :tool-calls tool-calls
                             :usage      turn-usage}
                      (and (number? gauge) (pos? gauge))
                      (assoc :gauge-prompt-tokens gauge))))))))))
    (finally
      (reset! drive-tool-fn* nil)
      (reset! on-driven-tool-cycle* nil))))

;; endregion ^^^^^ LoopDriver ^^^^^

;; region ----- Public API -----

(defn- chat* [request _provider-name cfg]
  (let [result (invoke! cfg request (driven? request))]
    (cond
      (:weather result) (weather-response (:weather result) (:model request))
      (failed? result) (error-response result)
      (driven? request) (parse-stream-json-response (:out result) (:model request))
      :else (let [{:keys [text usage]} (parse-json-output (:out result))]
              {:content text :model (:model request) :stop-reason :end-turn :tool-calls [] :usage usage}))))

(defn- stream-once [request on-chunk cfg]
  (let [streaming? (or (driven? request) (:stream-non-tool-turns cfg) (:streamNonToolTurns cfg))
        result     (invoke! cfg request streaming?)
        json?      (not streaming?)]
    (if-let [weather (:weather result)]
      (weather-response weather (:model request))
      (if-not (failed? result)
        (if json?
        (let [{:keys [text usage]} (parse-json-output (:out result))
              response {:content text :model (:model request) :stop-reason :end-turn :tool-calls [] :usage usage}]
          (when (and (seq text) (not (:error response)))
            (on-chunk {:text-delta text}))
          response)
        (let [lines   (str/split-lines (:out result))
              {:keys [deltas reasoning usage]} (parse-stream-json-output lines)
              parsed  (parse-stream-json-response (:out result) (:model request))
              content (or (not-empty (str/join deltas))
                          (:content parsed)
                          "")]
          (doseq [think reasoning]
            (when (seq think)
              (on-chunk {:reasoning-delta think})))
          (doseq [delta deltas]
            (when (seq delta)
              (on-chunk {:text-delta delta})))
          (let [response (if (seq (:tool-calls parsed))
                           parsed
                           {:content content :model (:model request) :stop-reason :end-turn :tool-calls [] :usage usage})
                think    (str/join reasoning)]
            (if (str/blank? think)
              (dissoc response :reasoning)
              (assoc response :reasoning {:summary think})))))
        (error-response result)))))

(defn chat [request provider-name cfg]
  (chat* request provider-name cfg))

(defn chat-stream [request on-chunk _provider-name cfg]
  (stream-once request on-chunk cfg))

(defn followup-messages [request response tool-calls tool-results]
  (let [assistant-msg {:role "assistant" :content (:content response "")}
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
    ;; The driver is the only tool-loop implementation for this provider.
    (tool-loop/install-provider-driver! claude-loop-driver)
    (assoc cfg :drives-tool-loop? true))
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build opts)))

(defn make [name cfg]
  (let [cfg (remembered-config cfg)]
    (warn-missing-oauth-token! name cfg)
    (tool-loop/install-provider-driver! claude-loop-driver)
    (->ClaudeCliAPI name cfg)))

;; endregion ^^^^^ Public API ^^^^^
