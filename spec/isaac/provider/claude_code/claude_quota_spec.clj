(ns isaac.provider.claude-code.claude-quota-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.provider.claude-code.api.claude-cli :as cli]
    [isaac.provider.claude-code.quota :as sut]
    [speclj.core :refer [after before describe it should=]]))

(defn- event [info]
  (json/generate-string {:type "rate_limit_event" :rate_limit_info info}))

(describe "Claude Code subscription quota"
  (before (cli/clear-fake-cli!) (cli/set-stub! (constantly {:exit 1 :err "no rate limit event" :out ""})) (sut/clear!))
  (after (cli/clear-stub!) (cli/clear-fake-cli!) (sut/clear!))

  (it "recovers a recorded window from the root after memory is cleared"
    (nexus/-with-nested-nexus {:root "/quota-spec" :fs (fs/mem-fs)}
      (sut/record! "first" {:type "rate_limit_event"
                             :rate_limit_info {:status "allowed" :rateLimitType "five_hour"
                                               :utilization 0.31 :resetsAt 1793192400}})
      (sut/clear!)
      (should= 31.0 (get-in (sut/meter {:id "first"}) [:windows 0 :used-percent]))))

  (it "does not probe a snapshot nine minutes old"
    (nexus/-with-nested-nexus {:root "/quota-spec" :fs (fs/mem-fs)}
      (with-redefs [sut/now (constantly (java.util.Date. 1000000))
                    cli/probe! (fn [_] (throw (Exception. "unexpected probe")))]
        (sut/record! "first" {:type "rate_limit_event"
                               :rate_limit_info {:rateLimitType "five_hour" :utilization 0.31
                                                 :resetsAt 1793192400}}))
      (sut/clear!)
      (with-redefs [sut/now (constantly (java.util.Date. 1540000))
                    cli/probe! (fn [_] (throw (Exception. "unexpected probe")))]
        (should= 31.0 (get-in (sut/meter {:id "first"}) [:windows 0 :used-percent])))))

  (it "probes a snapshot older than ten minutes"
    (nexus/-with-nested-nexus {:root "/quota-spec" :fs (fs/mem-fs)}
      (with-redefs [sut/now (constantly (java.util.Date. 1000000))]
        (sut/record! "first" {:type "rate_limit_event"
                               :rate_limit_info {:rateLimitType "five_hour" :utilization 0.31
                                                 :resetsAt 1793192400}}))
      (sut/clear!)
      (with-redefs [sut/now (constantly (java.util.Date. 1600001))
                    cli/probe! (fn [_] (sut/record! "first" {:type "rate_limit_event"
                                                               :rate_limit_info {:rateLimitType "five_hour"
                                                                                 :utilization 0.27 :resetsAt 1793192400}}))]
        (should= 27.0 (get-in (sut/meter {:id "first"}) [:windows 0 :used-percent])))))

  (it "probes with the provider login, Haiku, no tools or persistence and records the result"
    (nexus/-with-nested-nexus {:root "/quota-spec" :fs (fs/mem-fs)}
      (let [calls (atom [])]
        (cli/set-stub! (fn [invocation]
                         (swap! calls conj invocation)
                         {:exit 0 :err "" :out (event {:rateLimitType "five_hour"
                                                       :utilization 0.27 :resetsAt 1793192400})}))
        (should= 27.0 (get-in (sut/meter {:id "first" :command "custom-claude"
                                            :env {"CLAUDE_CONFIG_DIR" "/other-login"}})
                              [:windows 0 :used-percent]))
        (should= 1 (count @calls))
        (should= "custom-claude" (first (:argv (first @calls))))
        (should= "/other-login" (get-in (first @calls) [:env "CLAUDE_CONFIG_DIR"]))
        (should= "haiku" (second (drop-while #(not= "--model" %) (:argv (first @calls)))))
        (should= "stream-json" (second (drop-while #(not= "--output-format" %) (:argv (first @calls)))))
        (should= "" (second (drop-while #(not= "--tools" %) (:argv (first @calls)))))
        (should= true (boolean (some #{"--no-session-persistence"} (:argv (first @calls))))))))

  (it "returns the CLI failure as an unknown report rather than an exception"
    (nexus/-with-nested-nexus {:root "/quota-spec" :fs (fs/mem-fs)}
      (cli/set-stub! (constantly {:exit 1 :err "claude: not logged in" :out ""}))
      (should= :unknown (:status (sut/meter {:id "first" :command "claude"})))
      (should= "Claude CLI exit 1: claude: not logged in" (:error (sut/meter {:id "first" :command "claude"})))))

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
