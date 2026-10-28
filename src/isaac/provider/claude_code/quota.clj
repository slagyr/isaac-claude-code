(ns isaac.provider.claude-code.quota
  "Latest Claude CLI rate limit event per configured provider instance."
  (:require
    [clojure.string :as str]))

(defonce ^:private events* (atom {}))

(defn clear! []
  (reset! events* {}))

(defn record! [provider-id event]
  (when (and provider-id (= "rate_limit_event" (:type event)) (map? (:rate_limit_info event)))
    (swap! events* assoc (str provider-id) {:info (:rate_limit_info event)
                                            :checked-at (java.util.Date.)})))

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

(defn meter [{:keys [id]}]
  (if-let [{:keys [info checked-at]} (get @events* (str id))]
    (let [entries (or (seq (:unifiedWindows info))
                      (when (:rateLimitType info)
                        [[(:rateLimitType info) {:utilization (:utilization info)
                                                 :resetsAt (:resetsAt info)}]]))]
      {:checked-at checked-at :windows (mapv (partial window info) entries)})
    {:status :unknown :windows [] :error "No turn has reported a rate limit event yet."}))

(defn factory [_] meter)
