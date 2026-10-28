(ns isaac.provider.claude-code.quota
  "Latest Claude CLI rate limit event per configured provider instance."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]))

(defn now [] (java.util.Date.))

(def ^:private freshness-ms 600000)

(defn- snapshot-path [provider-id]
  (when-let [root (loader/root)]
    (str root "/cache/quota/" (java.net.URLEncoder/encode (str provider-id) "UTF-8") ".edn")))

(defn- disk-snapshot [provider-id]
  (when-let [path (snapshot-path provider-id)]
    (let [filesystem (fs/instance)]
      (when (fs/exists? filesystem path)
        (try
          (let [snapshot (edn/read-string (fs/slurp filesystem path))]
            (when (and (map? (:info snapshot)) (instance? java.util.Date (:checked-at snapshot)))
              snapshot))
          (catch Exception _ nil))))))

(defonce ^:private events* (atom {}))

(defn clear! []
  (reset! events* {}))

(defn record! [provider-id event]
  (when (and provider-id (= "rate_limit_event" (:type event)) (map? (:rate_limit_info event)))
    (let [snapshot {:info (:rate_limit_info event) :checked-at (now)}]
      (swap! events* assoc (str provider-id) snapshot)
      (when-let [path (snapshot-path provider-id)]
        (let [filesystem (fs/instance)]
          (fs/mkdirs filesystem (fs/parent path))
          (fs/spit filesystem path (pr-str snapshot)))))))

(defn- window-label [id]
  (let [[n unit] (str/split (name id) #"_")
        count*  ({"five" 5 "seven" 7} n)]
    (str (or count* n) "-" unit)))

(defn- window [info [id {:keys [utilization resetsAt]}]]
  {:id           (keyword (name id))
   :label        (window-label id)
   :used-percent (* 100.0 (double utilization))
   :resets-at    (java.util.Date/from (java.time.Instant/ofEpochSecond (long resetsAt)))
   :limited?     (boolean (and (= "rejected" (:status info))
                              (= (name id) (:rateLimitType info))))})

(defn- fresh? [{:keys [checked-at]}]
  (and (instance? java.util.Date checked-at)
       (<= 0 (- (.getTime ^java.util.Date (now)) (.getTime ^java.util.Date checked-at)) freshness-ms)))

(defn- snapshot [id]
  ;; Disk is authoritative across processes; the atom only serves roots without persistence.
  (or (disk-snapshot id) (get @events* (str id))))

(defn- probe! [config]
  (try
    ((requiring-resolve 'isaac.provider.claude-code.api.claude-cli/probe!) config)
    (catch Exception e
      (or (.getMessage e) (str e)))))

(defn meter [{:keys [id] :as config}]
  (let [previous (snapshot id)
        failure (when-not (fresh? previous) (probe! config))
        {:keys [info checked-at] :as current} (snapshot id)]
    (if (and current (or (fresh? previous) (and (nil? failure) (fresh? current))))
      (let [entries (or (seq (:unifiedWindows info))
                        (when (:rateLimitType info)
                          [[(:rateLimitType info) {:utilization (:utilization info)
                                                   :resetsAt (:resetsAt info)}]]))]
        {:checked-at checked-at :windows (mapv (partial window info) entries)})
      {:status :unknown :windows [] :error (or failure "No rate limit event from Claude CLI.")})))

(defn factory [_] meter)
