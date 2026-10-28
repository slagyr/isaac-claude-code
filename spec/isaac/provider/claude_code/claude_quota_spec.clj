(ns isaac.provider.claude-code.claude-quota-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.provider.claude-code.api.claude-cli :as cli]
    [isaac.provider.claude-code.quota :as sut]
    [speclj.core :refer [after before describe it should=]]))

(defn- event [info]
  (json/generate-string {:type "rate_limit_event" :rate_limit_info info}))

(describe "Claude Code subscription quota"
  (before (cli/clear-stub!) (cli/clear-fake-cli!) (sut/clear!))
  (after (cli/clear-stub!) (cli/clear-fake-cli!) (sut/clear!))

  (it "reports no turn for a provider that has not received a rate limit event"
    (should= :unknown (:status (sut/meter {:id "second"})))
    (should= [] (:windows (sut/meter {:id "second"}))))

  (it "reads the CLI windows in order and marks only a rejected window limited"
    (let [info {:status "rejected" :rateLimitType "seven_day"
                :unifiedWindows {:five_hour {:utilization 0.31 :resetsAt 1793192400}
                                 :seven_day {:utilization 1.0 :resetsAt 1793610000}}}]
      (sut/record! "first" {:type "rate_limit_event" :rate_limit_info info})
      (should= [{:id :five_hour :label "5-hour" :used-percent 31.0
                 :resets-at (java.util.Date/from (java.time.Instant/ofEpochSecond 1793192400)) :limited? false}
                {:id :seven_day :label "7-day" :used-percent 100.0
                 :resets-at (java.util.Date/from (java.time.Instant/ofEpochSecond 1793610000)) :limited? true}]
               (:windows (sut/meter {:id "first"})))
      (should= :unknown (:status (sut/meter {:id "second"})))))

  (it "falls back to the CLI's flat rate limit fields"
    (sut/record! "first" {:type "rate_limit_event"
                            :rate_limit_info {:status "allowed" :rateLimitType "five_hour"
                                              :utilization 0.5 :resetsAt 1793192400}})
    (should= 50.0 (get-in (sut/meter {:id "first"}) [:windows 0 :used-percent])))

  (it "captures rate limit events from a driven turn's stream output"
    (cli/set-stub! (constantly {:exit 0 :err ""
                                :out (str/join "\n" [(event {:status "allowed" :rateLimitType "five_hour"
                                                                :unifiedWindows {:five_hour {:utilization 0.31 :resetsAt 1793192400}}})
                                                       (json/generate-string {:type "result" :result "done"})])}))
    (let [provider (cli/make "first" {:command "claude"})]
      (api/chat provider {:model "sonnet" :tools [{:type "function" :function {:name "exec__run"}}]
                          :messages [{:role "user" :content "hi"}]})
      (should= 31.0 (get-in (sut/meter {:id "first"}) [:windows 0 :used-percent]))))

  (it "captures rate limit events from a raw shell-out's JSON output"
    (cli/set-stub! (constantly {:exit 0 :err ""
                                :out (str/join "\n" [(event {:status "allowed" :rateLimitType "five_hour"
                                                                :unifiedWindows {:five_hour {:utilization 0.4 :resetsAt 1793192400}}})
                                                       (json/generate-string {:type "result" :result "done"})])}))
    (api/chat (cli/make "first" {:command "claude"})
              {:model "sonnet" :messages [{:role "user" :content "hi"}]})
    (should= 40.0 (get-in (sut/meter {:id "first"}) [:windows 0 :used-percent]))))
