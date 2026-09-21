(ns isaac.llm.claude-cli-steps
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.fs :as fs]
    [isaac.tool.tools-steps :as tools-steps]
    [isaac.llm.api.claude-cli :as claude-cli]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.providers :as providers]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]
    [isaac.step-tables :as match]))

(helper! isaac.llm.claude-cli-steps)

;; region ----- Helpers -----

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- expand-home [path]
  (if (str/starts-with? path "~/")
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- parse-stream-chunks [raw]
  (let [trimmed (str/trim raw)]
    (if (str/starts-with? trimmed "[")
      (edn/read-string trimmed)
      (edn/read-string (str "[" trimmed "]")))))

(defn- argv->arg-map [argv]
  (loop [args (vec argv)
         m    {}]
    (if (empty? args)
      m
      (let [a (first args)]
        (if (str/starts-with? a "--")
          (let [next (second args)
                has-value? (and next (not (str/starts-with? next "--")))]
            (recur (drop (if has-value? 2 1) args)
                   (if has-value?
                     (assoc m a next)
                     (assoc m a ""))))
          (recur (rest args) m))))))

(defn- prompt-text [inv]
  (:in inv))

(defn- system-prompt-arg [argv]
  (get (argv->arg-map argv) "--system-prompt"))

(defn- match-invocation-table [inv table]
  (let [{:keys [argv env]} inv
        arg-map  (argv->arg-map argv)
        prompt   (prompt-text inv)
        failures (atom [])]
    (doseq [row (:rows table)]
      (let [[arg value] row]
        (cond
          (= arg "(full prompt text as arg)")
          (when (str/blank? prompt)
            (swap! failures conj "expected conversation prompt on stdin"))

          (= arg "(conversation prompt as final arg)")
          (when (str/blank? prompt)
            (swap! failures conj "expected conversation prompt on stdin"))

          (= arg "(conversation prompt on stdin)")
          (when (str/blank? prompt)
            (swap! failures conj "expected conversation prompt on stdin"))

          (= arg "(system prompt contains protocol contract)")
          (let [system (system-prompt-arg argv)]
            (when-not (and system (str/includes? system claude-cli/tool-protocol-contract))
              (swap! failures conj "system prompt missing tool protocol contract")))

          (= arg "(user prompt does not contain protocol contract)")
          (when (and prompt (str/includes? prompt claude-cli/tool-protocol-contract))
            (swap! failures conj "tool protocol contract leaked into user prompt"))

          (= arg "(system prompt contains soul text)")
          (let [system (system-prompt-arg argv)]
            (when-not (and system (str/includes? system "Think hard."))
              (swap! failures conj "system prompt missing soul text")))

          (= arg "(user prompt does not contain soul text)")
          (when (and prompt (str/includes? prompt "Think hard."))
            (swap! failures conj "soul text leaked into user prompt"))

          (= arg "(prompt arg contains full history)")
          (when-not (and prompt
                         (str/includes? prompt "previous turn")
                         (str/includes? prompt "previous reply"))
            (swap! failures conj "prompt missing prior transcript history"))

          (= arg "(stdin contains full history)")
          (when-not (and prompt
                         (str/includes? prompt "previous turn")
                         (str/includes? prompt "previous reply"))
            (swap! failures conj "stdin missing prior transcript history"))

          (= arg "(no --continue or --resume)")
          (when (or (some #(= % "--continue") argv)
                    (some #(= % "--resume") argv))
            (swap! failures conj "found --continue or --resume in argv"))

          (= arg "(no ANTHROPIC_API_KEY in env)")
          (when (contains? env "ANTHROPIC_API_KEY")
            (swap! failures conj "ANTHROPIC_API_KEY present in subprocess env"))

          (= arg "(ISAAC_MCP_NONCE in env)")
          (when (str/blank? (get env "ISAAC_MCP_NONCE"))
            (swap! failures conj "ISAAC_MCP_NONCE missing from subprocess env"))

          (re-matches #"\(env [^=]+=.*\)" arg)
          (let [[_ k v] (re-matches #"\(env ([^=]+)=(.*)\)" arg)]
            (when-not (= v (get env k))
              (swap! failures conj (str "expected env " k "=" v " got " (pr-str (get env k))))))

          (re-matches #"\(no [^ ]+ in env\)" arg)
          (let [[_ k] (re-matches #"\(no ([^ ]+) in env\)" arg)]
            (when (contains? env k)
              (swap! failures conj (str k " present in subprocess env"))))

          (str/starts-with? arg "--")
          (if (str/blank? value)
            (when-not (contains? arg-map arg)
              (swap! failures conj (str "missing flag " arg)))
            (let [actual (get arg-map arg)]
              (if (str/starts-with? (str value) "#\"")
                (let [pattern (re-pattern (str "(?s)" (second (re-matches #"#\"(.+)\"" value))))]
                  (when-not (and actual (re-find pattern (str actual)))
                    (swap! failures conj (str "expected " arg " matching " value " got " actual))))
                (when-not (= value actual)
                  (swap! failures conj (str "expected " arg " = " value " got " actual))))))

          :else
          (swap! failures conj (str "unknown table arg " arg)))))
    @failures))

(defn- declare-module! []
  (when-let [root (g/get :root)]
    (let [coord {:local/root (System/getProperty "user.dir")}
          path  (str root "/config/isaac.edn")
          fs*   (mem-fs)
          cfg   (if (fs/exists? fs* path)
                  (edn/read-string (fs/slurp fs* path))
                  {})]
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path (pr-str (assoc-in cfg [:modules :isaac.provider.claude-code] coord)))
      (providers/register! "claude-code" {:api                        "claude-cli"
                                           :auth                       "none"
                                           :command                    "claude"
                                           :stream-supports-tool-calls false}))))

(defn- install-stub! [f]
  (declare-module!)
  (g/dissoc! :feature-config)
  (claude-cli/clear-invocations!)
  (claude-cli/set-stub! f))

(defn- json-result-out [text & [{:keys [usage]}]]
  (json/generate-string
    (cond-> {:type   "result"
             :result text}
      usage (assoc :usage usage))))

(defn- stream-json-out [chunks & [{:keys [usage]}]]
  (let [delta-lines (map (fn [text]
                           (json/generate-string {:type "content_block_delta"
                                                  :delta {:text text}}))
                         chunks)
        result-line (json/generate-string
                      {:type   "result"
                       :result (apply str chunks)
                       :usage  (or usage {:input_tokens  42
                                          :output_tokens 7
                                          :cache_read_input_tokens      3
                                          :cache_creation_input_tokens 1})})]
    (str/join "\n" (conj (vec delta-lines) result-line))))

(defn- stub-return [text]
  {:exit 0 :out (json-result-out text {:usage {:input_tokens  100
                                              :output_tokens 25
                                              :cache_read_input_tokens      0
                                              :cache_creation_input_tokens 0}}) :err ""})

(defn- stub-fail [exit message]
  {:exit exit :out "" :err message})

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Given: Claude binary stub -----

(defn claude-binary-stubbed-return [text]
  (install-stub! (constantly (stub-return text))))

(defn claude-binary-at-stubbed-return [path text]
  (g/assoc! :claude-binary-path path)
  (install-stub! (fn [{:keys [argv]}]
                   (g/should= path (first argv))
                   (stub-return text))))

(defn claude-binary-stubbed-stream [raw]
  (let [chunks (parse-stream-chunks raw)]
    (g/should (pos? (count chunks)))
    (install-stub! (constantly {:exit 0 :out (stream-json-out chunks) :err ""}))))

(defn claude-binary-stubbed-tool-then-text []
  (let [state (atom 0)]
    (install-stub!
      (fn [_]
        (case (swap! state inc)
          1 (stub-return (str "<tool_call>"
                              (json/generate-string {:name "exec" :arguments {:command "ls"}})
                              "</tool_call>"))
          2 (stub-return "done")
          (stub-return "done"))))))

(defn claude-binary-stubbed-json-with-usage [text input output]
  (install-stub!
    (constantly
      {:exit 0
       :out  (json-result-out text {:usage {:input_tokens  (parse-long input)
                                            :output_tokens (parse-long output)}})
       :err  ""})))

(defn claude-binary-stubbed-json-no-usage [text]
  (install-stub!
    (constantly {:exit 0 :out (json-result-out text) :err ""})))

(defn claude-binary-stubbed-stream-with-usage [raw]
  (claude-binary-stubbed-stream raw))

(defn claude-binary-stubbed-sequence [table]
  (let [responses (atom (mapv first (:rows table)))]
    (install-stub!
      (fn [_]
        (let [response (or (first @responses) "")]
          (swap! responses #(vec (rest %)))
          (stub-return response))))))

(defn claude-binary-stubbed-fail [exit message]
  (install-stub! (constantly (stub-fail (parse-long exit) message))))

;; endregion ^^^^^ Given: Claude binary stub ^^^^^

;; region ----- Then: Claude binary invocation -----

(defn claude-binary-invoked-once-with [table]
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should= 1 (count invocations))
    (g/should= [] (match-invocation-table (first invocations) table))))

(defn claude-binary-at-invoked-once-with [path table]
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should= 1 (count invocations))
    (let [inv (first invocations)]
      (g/should= path (first (:argv inv)))
      (g/should= [] (match-invocation-table inv table)))))

(defn claude-binary-invoked-exactly [n]
  (session-steps/await-turn!)
  (g/should= (if (string? n) (parse-long n) n) (count (claude-cli/invocations))))

(defn second-invocation-includes-tool-result []
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should (<= 2 (count invocations)))
    (let [prompt (:in (second invocations))]
      (g/should (and prompt (str/includes? prompt "Tool result for exec"))))))

;; endregion ^^^^^ Then: Claude binary invocation ^^^^^

;; region ----- Supporting steps -----

(defn response-is [expected]
  (session-steps/await-turn!)
  (let [output (g/get :output)
        result (g/get :llm-result)]
    (g/should= expected (or (not-empty output)
                            (:content result)
                            (get-in result [:response :content])
                            (get-in result [:message :content])
                            (get-in result [:response :message :content])))))

(defn response-streams-as [raw]
  (session-steps/await-turn!)
  (let [expected (parse-stream-chunks raw)
        events   (->> (or (some-> (g/get :channel-events) deref) [])
                      (filter #(#{"text-chunk" "chatter"} (:event %)))
                      (mapv :text))]
    (g/should= expected events)))

(defn crew-has-tools [raw]
  (tools-steps/builtin-tools-registered)
  (let [tools (-> raw
                  (str/replace #"[\[\]]" "")
                  (str/split #",\s*")
                  (->> (map str/trim)
                       (remove str/blank?)
                       vec))]
    (session-steps/crew-tool-allow "thinker" (str/join "," tools))))

(defn- exec-tool-events []
  (->> (or (some-> (g/get :channel-events) deref) [])
       (filter #(and (= "tool-call" (:event %))
                     (contains? #{"exec" "exec__run"} (get-in % [:tool :name]))))))

(defn exec-tool-executed []
  (session-steps/await-turn!)
  (g/should (seq (exec-tool-events))))

(defn exec-tool-executed-n [n]
  (session-steps/await-turn!)
  (g/should= (if (string? n) (parse-long n) n) (count (exec-tool-events))))

(defn- commands-from-text [text]
  (mapv (fn [[_ json-cmd invoke-cmd]] (or json-cmd invoke-cmd))
        (re-seq #"(?:\"command\":\"([^\"]+)\"|<parameter name=\"command\">([^<]+)</parameter>)" (str text))))

(defn exec-tool-ran-commands [table]
  (session-steps/await-turn!)
  (let [expected  (mapv #(or (get (zipmap (:headers table) %) "command")
                             (first %))
                        (:rows table))
        first-out (or (get-in (first (claude-cli/invocations)) [:out]) "")
        text      (or (try (:result (json/parse-string first-out true))
                           (catch Exception _ first-out))
                      first-out)
        actual    (commands-from-text text)]
    (g/should= expected actual)))

(defn second-invocation-prompt-contains [text]
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should (<= 2 (count invocations)))
    (g/should (str/includes? (str (:in (second invocations))) text))))

(defn session-has-no-transcript-containing [name text]
  (session-steps/await-turn!)
  (let [entries (or (try ((requiring-resolve 'isaac.session.store.spi/get-transcript)
                          ((requiring-resolve 'isaac.session.store.spi/registered-store))
                          name)
                         (catch Exception _ nil))
                    [])
        blob    (pr-str entries)]
    (g/should-not (str/includes? blob text))))

(defn session-has-no-transcript-role-containing [name role text]
  (session-steps/await-turn!)
  (let [entries (or (try ((requiring-resolve 'isaac.session.store.spi/get-transcript)
                          ((requiring-resolve 'isaac.session.store.spi/registered-store))
                          name)
                         (catch Exception _ nil))
                    [])
        blob    (->> entries
                     (filter #(= role (or (get-in % [:message :role]) (:role %))))
                     pr-str)]
    (g/should-not (str/includes? blob text))))

(defn- turn-result []
  (or (g/get :llm-result) (g/get :dispatch-result)))

(defn- loud-error? [result]
  (or (= :llm-error (:error result))
      (some? (:error result))
      (and (:unavailable? result)
           (= :auth (:reason result)))))

(defn turn-ends-with-error [kw]
  (session-steps/await-turn!)
  (let [result   (turn-result)
        expected (if (string? kw) (keyword (str/replace kw #":" "")) kw)]
    (g/should= expected (:error result))))

(defn claude-binary-error-reported []
  (session-steps/await-turn!)
  (g/should (loud-error? (turn-result))))

(defn error-message-contains [fragment]
  (session-steps/await-turn!)
  (let [result  (turn-result)
        message (or (:message result) (:output result) "")]
    (g/should (str/includes? message fragment))))

(defn error-classified-auth []
  (session-steps/await-turn!)
  (let [result (turn-result)]
    (g/should (:unavailable? result))
    (g/should= :auth (:reason result))))



(defn credentials-file-exists [_path]
  (let [path (expand-home "~/.claude/.credentials.json")
        fs*  (mem-fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path "{\"claudeAiOauth\":{\"accessToken\":\"sub-token\"}}")))

(defn anthropic-api-key-unset []
  (g/assoc! :anthropic-api-key-cleared? true))

(g/before-scenario
  (fn []
    (providers/register! "claude-code" {:api                        "claude-cli"
                                         :auth                       "none"
                                         :command                    "claude"
                                         :stream-supports-tool-calls false})
    (claude-cli/clear-stub!)
    (claude-cli/clear-invocations!)
    (claude-cli/clear-fake-cli!)))

(g/after-scenario
  (fn []
    (g/dissoc! :anthropic-api-key-cleared?)
    (claude-cli/clear-stub!)
    (claude-cli/clear-invocations!)
    (claude-cli/clear-fake-cli!)))

;; endregion ^^^^^ Supporting steps ^^^^^

;; region ----- Routing -----

(defgiven #"the claude binary is stubbed to return \"([^\"]+)\"" isaac.llm.claude-cli-steps/claude-binary-stubbed-return)

(defgiven #"the claude binary at \"([^\"]+)\" is stubbed to return \"([^\"]+)\""
  isaac.llm.claude-cli-steps/claude-binary-at-stubbed-return)

(defgiven #"the claude binary is stubbed to stream (.+)" isaac.llm.claude-cli-steps/claude-binary-stubbed-stream)

(defgiven #"the claude binary is stubbed to return json with usage \"([^\"]+)\" and tokens (\d+) and (\d+)"
  isaac.llm.claude-cli-steps/claude-binary-stubbed-json-with-usage)

(defgiven #"the claude binary is stubbed to return json without usage \"([^\"]+)\""
  isaac.llm.claude-cli-steps/claude-binary-stubbed-json-no-usage)

(defgiven #"the claude binary is stubbed to stream-json with terminal usage (.+)"
  isaac.llm.claude-cli-steps/claude-binary-stubbed-stream-with-usage)

(defgiven "the claude binary is stubbed to first return tool call text for exec, then \"done\""
  isaac.llm.claude-cli-steps/claude-binary-stubbed-tool-then-text)

(defgiven "the claude binary is stubbed to return in sequence:"
  isaac.llm.claude-cli-steps/claude-binary-stubbed-sequence)

(defgiven #"the claude binary is stubbed to fail with exit code (\d+) and message \"([^\"]+)\""
  isaac.llm.claude-cli-steps/claude-binary-stubbed-fail)

(defthen "the claude binary was invoked exactly once with:" isaac.llm.claude-cli-steps/claude-binary-invoked-once-with)

(defthen #"the claude binary at \"([^\"]+)\" was invoked exactly once with:"
  isaac.llm.claude-cli-steps/claude-binary-at-invoked-once-with)

(defn claude-binary-invoked-twice []
  (claude-binary-invoked-exactly "2"))

(defthen "the claude binary was invoked exactly twice" isaac.llm.claude-cli-steps/claude-binary-invoked-twice)

(defthen "the second invocation included the tool result serialized in the prompt text"
  isaac.llm.claude-cli-steps/second-invocation-includes-tool-result)

(defthen #"the response is \"([^\"]+)\"" isaac.llm.claude-cli-steps/response-is)

(defthen #"the response streams as (.+)" isaac.llm.claude-cli-steps/response-streams-as)

(defgiven #"the crew has tools: (.+)" isaac.llm.claude-cli-steps/crew-has-tools)

(defthen "the exec tool is executed" isaac.llm.claude-cli-steps/exec-tool-executed)

(defthen "the claude binary was invoked exactly {n:int} times"
  isaac.llm.claude-cli-steps/claude-binary-invoked-exactly)

;; Agent session_steps now registers the same phrase; keep the local helper
;; for await-turn! but do not re-register the Then (gherclj classify-step).

(defthen "the exec tool ran commands in order:"
  isaac.llm.claude-cli-steps/exec-tool-ran-commands)

(defthen "the second invocation's prompt text contains {text:string}"
  isaac.llm.claude-cli-steps/second-invocation-prompt-contains)

(defthen "session {name:string} has no transcript entry containing {text:string}"
  isaac.llm.claude-cli-steps/session-has-no-transcript-containing)

(defthen "session {name:string} has no transcript entry with role {role:string} containing {text:string}"
  isaac.llm.claude-cli-steps/session-has-no-transcript-role-containing)

(defthen "the turn ends with error {kw:string}"
  isaac.llm.claude-cli-steps/turn-ends-with-error)

(defn hail-delivery-bound-to-session [name]
  (g/assoc! :hail-delivery {:id "hail-jkx7" :bound-session name :attempts 0}))

(defn hail-delivery-runs-its-turn []
  (session-steps/user-sends-on-session
    "run it" (or (:bound-session (g/get :hail-delivery)) "main"))
  (session-steps/await-turn!)
  (let [result   (or (g/get :llm-result) (g/get :dispatch-result) {})
        delivery (or (g/get :hail-delivery) {:attempts 0})]
    (if (:unavailable? result)
      (do
        (log/info :hail/deferred :error (:error result))
        (g/assoc! :hail-delivery (assoc delivery :deferred? true :attempts (:attempts delivery))))
      (g/assoc! :hail-delivery (update delivery :attempts (fnil inc 0))))))

(defn delivery-is-deferred-with-attempts [n]
  (let [delivery (g/get :hail-delivery)
        n (if (string? n) (parse-long n) n)]
    (g/should (:deferred? delivery))
    (g/should= n (:attempts delivery))))

(defgiven "a hail delivery is bound to session {name:string}"
  isaac.llm.claude-cli-steps/hail-delivery-bound-to-session)

(defwhen "the hail delivery runs its turn"
  isaac.llm.claude-cli-steps/hail-delivery-runs-its-turn)

(defthen "the delivery is deferred with attempts {n:int}"
  isaac.llm.claude-cli-steps/delivery-is-deferred-with-attempts)

(defthen "an error is reported indicating the claude binary failed"
  isaac.llm.claude-cli-steps/claude-binary-error-reported)

(defthen #"the error message contains \"([^\"]+)\"" isaac.llm.claude-cli-steps/error-message-contains)

(defthen "the error is classified as auth-unavailable" isaac.llm.claude-cli-steps/error-classified-auth)

(defgiven #"the file \"([^\"]+)\" exists with the subscription login"
  isaac.llm.claude-cli-steps/credentials-file-exists)

(defgiven "ANTHROPIC_API_KEY is not set in the environment" isaac.llm.claude-cli-steps/anthropic-api-key-unset)

;; region ----- Fake Claude Code (isaac-5xn7) -----

(defn- parse-script-table [table]
  (mapv (fn [row]
          (let [m (zipmap (:headers table) row)]
            {:cycle   (parse-long (str (get m "cycle")))
             :kind    (str (get m "kind"))
             :payload (str (get m "payload"))}))
        (:rows table)))

(defn fake-claude-code-scripted [table]
  (declare-module!)
  (g/dissoc! :feature-config)
  (api/register! :claude-cli claude-cli/make)
  (g/update! :provider-configs #(assoc (or % {}) "claude-code" {:api "claude-cli"
                                                                  :command "claude"
                                                                  :drives-tool-loop? true
                                                                  :stream-supports-tool-calls false}))
  (claude-cli/clear-invocations!)
  (claude-cli/clear-stub!)
  (claude-cli/set-fake-cli! (parse-script-table table)))

(defn log-has-n-entries-with-event [n event]
  (let [want (-> (str event)
                 (str/replace #"^\"|\"$" "")
                 (str/replace #"^:" "")
                 keyword)
        cnt  (count (filter #(= want (:event %)) (log/get-entries)))]
    (g/should= (if (string? n) (parse-long n) n) cnt)))

(defn fake-claude-code-reports-tools-in-result-only []
  (claude-cli/report-tools-in-result-only!))

(defn fake-claude-code-fails-mcp-init []
  (claude-cli/fail-mcp-init!))

(defn fake-claude-code-exits-before-streaming [code stderr]
  (claude-cli/exit-before-streaming! (if (string? code) (parse-long code) code) stderr))

(defn fake-claude-code-received-on-stdin [table]
  (session-steps/await-turn!)
  (let [actual  (claude-cli/fake-cli-stdin)
        headers (:headers table)]
    (if (some #{"type" "message.role" "message.content"} headers)
      (let [result (match/match-entries table actual)]
        (g/should= [] (:failures result)))
      (let [expected (mapv (fn [row]
                             (let [m (zipmap headers row)]
                               {:role    (str (get m "role"))
                                :content (str (get m "content"))}))
                           (:rows table))]
        (g/should= expected actual)))))

(defn fake-claude-code-received-no-bare-stdin-lines []
  (session-steps/await-turn!)
  (let [actual (claude-cli/fake-cli-stdin)]
    (g/should (seq actual))
    (doseq [line actual]
      (g/should (= "user" (str (:type line))))
      (g/should (map? (:message line))))))

(defn mcp-config-names-server-running [name table]
  (session-steps/await-turn!)
  (let [saved  (claude-cli/last-mcp-config)
        server (get-in saved [:body :mcpServers (keyword name)])
        argv   (str/join " " (concat [(:command server)] (:args server)))
        regexes (map first (:rows table))]
    (g/should-not-be-nil server)
    (doseq [cell regexes]
      (let [pattern (if (str/starts-with? (str cell) "#\"")
                      (re-pattern (str "(?s)" (second (re-matches #"#\"(.+)\"" cell))))
                      (re-pattern (str cell)))]
        (g/should (re-find pattern argv))))))

(defn fake-claude-code-was-terminated []
  (session-steps/await-turn!)
  (g/should (claude-cli/fake-cli-terminated?)))

(defn fake-claude-code-invoked-with [table]
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should (seq invocations))
    (g/should (some #(empty? (match-invocation-table % table)) invocations))))

(defn fake-claude-code-invoked-exactly-once []
  (session-steps/await-turn!)
  (g/should= 1 (count (claude-cli/invocations))))

(defn turn-result-table [table]
  (session-steps/await-turn!)
  (let [result   (g/get :llm-result)
        expected (into {} (map (fn [row]
                                 (let [[k v] row]
                                   [(keyword k) (edn/read-string v)]))
                               (:rows table)))
        actual   (cond-> {}
                   (:status expected) (assoc :status (or (when-let [sr (:stopReason result)]
                                                           (keyword sr))
                                                         (some-> result :error)
                                                         (when (:cancelled? result) :cancelled))))]
    (g/should= expected actual)))

(defgiven "a fake Claude Code on the path scripted with:" isaac.llm.claude-cli-steps/fake-claude-code-scripted)
(defthen "the log has {int} entries with event {string}" isaac.llm.claude-cli-steps/log-has-n-entries-with-event
  "Counts log entries by :event — the repetition itself is the defect (isaac-8cur).")

(defgiven "the fake Claude Code reports tool calls only in its final result" isaac.llm.claude-cli-steps/fake-claude-code-reports-tools-in-result-only)
(defgiven "the fake Claude Code fails MCP initialization" isaac.llm.claude-cli-steps/fake-claude-code-fails-mcp-init)
(defgiven "the fake Claude Code exits {code:int} before streaming with stderr {text:string}"
  isaac.llm.claude-cli-steps/fake-claude-code-exits-before-streaming)
(defthen "the fake Claude Code received on stdin:" isaac.llm.claude-cli-steps/fake-claude-code-received-on-stdin)
(defthen "the fake Claude Code received no bare stdin lines" isaac.llm.claude-cli-steps/fake-claude-code-received-no-bare-stdin-lines)
(defthen "the MCP config handed to the fake Claude Code names server {name:string} running:"
  isaac.llm.claude-cli-steps/mcp-config-names-server-running)
(defthen "the fake Claude Code was terminated" isaac.llm.claude-cli-steps/fake-claude-code-was-terminated)
(defthen "the fake Claude Code was invoked with:" isaac.llm.claude-cli-steps/fake-claude-code-invoked-with)
(defthen "the fake Claude Code was invoked exactly once" isaac.llm.claude-cli-steps/fake-claude-code-invoked-exactly-once)
(defthen "the turn result is:" isaac.llm.claude-cli-steps/turn-result-table)

;; endregion ^^^^^ Fake Claude Code ^^^^^

;; endregion ^^^^^ Routing ^^^^^