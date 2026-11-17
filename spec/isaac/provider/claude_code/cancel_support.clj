(ns isaac.provider.claude-code.cancel-support
  "Real, bounded subprocess fixture for cancel specs and feature scenarios."
  (:require
    [clojure.java.io :as io]
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.foundation.logger :as log]
    [isaac.provider.claude-code.api.claude-cli :as claude-cli]))

(defn- await-file! [file]
  (let [deadline (+ (System/nanoTime) 3000000000)]
    (loop []
      (cond
        (.exists file) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 1) (recur))
        :else (throw (ex-info "Claude subprocess never started" {:file file}))))))

(defn fixture! []
  (claude-cli/clear-stub!)
  (claude-cli/clear-fake-cli!)
  (bridge-cancel/clear!)
  (let [output (log/output)]
    (log/set-output! :memory)
    (log/clear-entries!)
    (let [dir    (.toFile (java.nio.file.Files/createTempDirectory
                            "claude-cancel" (make-array java.nio.file.attribute.FileAttribute 0)))
          script (io/file dir "claude")]
      ;; Pass the marker in the environment: CLI flags are positional args.
      (spit script "#!/bin/sh\ntouch \"$ISAAC_CANCEL_MARKER\"\nexec sleep 8\n")
      (.setExecutable script true)
      {:dir dir :command (.getAbsolutePath script) :turns (atom []) :output output})))

(defn start! [{:keys [dir command turns]} session]
  (let [marker (io/file dir (str session ".started"))
        turn (bridge-cancel/begin-turn! session)
        result (future (claude-cli/chat {:model "haiku" :messages [{:role "user" :content "long task"}]}
                                        "claude"
                                        {:command command :session-key session
                                         :forward-env [] :env {"ISAAC_CANCEL_MARKER" (.getAbsolutePath marker)}}))
        running {:session session :turn turn :result result}]
    (swap! turns conj running)
    (await-file! marker)
    running))

(defn cancel! [{:keys [session]}]
  (let [at (System/nanoTime)]
    (bridge-cancel/cancel! session)
    at))

(defn ended-within? [{:keys [result]} cancel-at ms]
  (let [remaining (max 0 (- ms (quot (- (System/nanoTime) cancel-at) 1000000)))]
    (not= ::running (deref result remaining ::running))))

(defn still-running? [{:keys [result]} ms]
  (= ::running (deref result ms ::running)))

(defn terminated-count []
  (count (filter #(= :claude/driver-terminated (:event %)) (log/get-entries))))

(defn cleanup! [{:keys [dir turns output]}]
  (doseq [{:keys [session turn result]} @turns]
    (bridge-cancel/cancel! session)
    (deref result 10000 nil)
    (bridge-cancel/end-turn! session turn))
  (bridge-cancel/clear!)
  (doseq [file (.listFiles dir)] (.delete file))
  (.delete dir)
  (log/set-output! output))
