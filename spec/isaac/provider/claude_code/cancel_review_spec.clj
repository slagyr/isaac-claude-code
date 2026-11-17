(ns isaac.provider.claude-code.cancel-review-spec
  "Process-tree cleanup and isolation after a cancelled Claude turn."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.provider.claude-code.api.claude-cli :as sut]
    [speclj.core :refer [after before describe it should should=]]))

(defn- sh-quote [s] (str "'" (str/replace s "'" "'\\''") "'"))
(defn- line [m] (json/generate-string m))
(defn- text-line [s] (line {:type "stream_event" :event {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text s}}}))
(def ^:private result-line (line {:type "result" :result "done" :usage {:input_tokens 1200 :output_tokens 300}}))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "claude-review" (make-array java.nio.file.attribute.FileAttribute 0))))
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

(defn- alive? [pid]
  (.orElse (.map (java.lang.ProcessHandle/of pid)
                 (reify java.util.function.Function (apply [_ h] (.isAlive h))))
           false))

(defn- cancel-on-first-chunk! [request command session-key]
  (bridge-cancel/begin-turn! session-key)
  (let [chunks (atom [])
        cancel-at (atom nil)]
    (try
      (let [res (sut/chat-stream request
                                 (fn [{:keys [text-delta]}]
                                   (when text-delta
                                     (swap! chunks conj text-delta)
                                     (reset! cancel-at (System/nanoTime))
                                     (bridge-cancel/cancel! session-key)))
                                 "claude"
                                 {:command command :stream-non-tool-turns true :forward-env [] :session-key session-key})]
        {:res res :chunks @chunks :cancel-at @cancel-at})
      (finally (bridge-cancel/clear!)))))

(defn- dead-within-3s! [pids cancel-at]
  ;; Poll the actual pids, with a deadline measured from Stop, not a fixed
  ;; post-Stop delay that could hide a late cleanup.
  (let [deadline (+ cancel-at 3000000000)]
    (loop []
      (let [living (filterv alive? pids)]
        (if (empty? living)
          (should (<= (System/nanoTime) deadline))
          (if (< (System/nanoTime) deadline)
            (do (Thread/sleep 5) (recur))
            (should= [] living)))))))

(def ^:private plain {:model "haiku" :messages [{:role "user" :content "hi"}]})

(describe "stop-fast review"
  (before (sut/clear-stub!) (sut/clear-invocations!) (sut/clear-fake-cli!) (bridge-cancel/clear!) (reset! dir (temp-dir)))
  (after (bridge-cancel/clear!) (rm-rf @dir))

  (it "20 cancels in a row leave no claude and no child alive"
    (let [pids    (io/file @dir "pids")
          command (script! "claude-with-child"
                           (str "cat >/dev/null\n"
                                "sleep 30 & echo $! >> " (sh-quote (.getPath pids)) "\n"
                                "echo $$ >> " (sh-quote (.getPath pids)) "\n"
                                "trap 'sleep 1; exit 143' TERM\n"
                                (emit (text-line "partial")) "\n"
                                "sleep 20 & wait $!\n"))]
      (let [runs (vec (for [i (range 20)]
                        (let [{:keys [cancel-at]} (cancel-on-first-chunk! plain command (str "many-" i))]
                          {:pids (mapv parse-long (take-last 2 (str/split-lines (slurp pids))))
                           :cancel-at cancel-at})))]
        (should= 40 (count (str/split-lines (slurp pids))))
        (doseq [{:keys [pids cancel-at]} runs]
          (dead-within-3s! pids cancel-at)))))

  (it "a child that outlives a claude which exits at once on SIGTERM is still killed"
    ;; claude exits immediately on TERM, orphaning its child. The reaper must
    ;; still know about the child: descendants have to be captured before the
    ;; SIGTERM, not after the read returns.
    (let [pids    (io/file @dir "pids")
          command (script! "claude-fast-exit"
                           (str "cat >/dev/null\n"
                                "sleep 30 & echo $! >> " (sh-quote (.getPath pids)) "\n"
                                "trap 'exit 143' TERM\n"
                                (emit (text-line "partial")) "\n"
                                "sleep 20 & wait $!\n"))]
      (let [runs (vec (for [i (range 10)]
                        (let [{:keys [cancel-at]} (cancel-on-first-chunk! plain command (str "orphan-" i))]
                          {:pids (mapv parse-long (take-last 1 (str/split-lines (slurp pids))))
                           :cancel-at cancel-at})))]
        (should= 10 (count (str/split-lines (slurp pids))))
        (doseq [{:keys [pids cancel-at]} runs]
          (dead-within-3s! pids cancel-at)))))

  (it "a dying claude's late stdout never reaches the next turn"
    (let [late    (script! "claude-late"
                           (str "cat >/dev/null\n"
                                "late() { printf '%s\\n' " (sh-quote (text-line "LATE")) "; exit 143; }\n"
                                "trap late TERM\n"
                                (emit (text-line "partial")) "\n"
                                "sleep 20 & wait $!\n"))
          clean   (script! "claude-clean" (str "cat >/dev/null\n" (emit (text-line "next") result-line) "\n"))
          first   (cancel-on-first-chunk! plain late "late-session")
          chunks  (atom [])
          second  (sut/chat-stream plain #(some->> (:text-delta %) (swap! chunks conj)) "claude"
                                   {:command clean :stream-non-tool-turns true :forward-env [] :session-key "late-session"})]
      (should= ["partial"] (:chunks first))
      (should= ["next"] @chunks)
      (should= "next" (:content second))))
)
