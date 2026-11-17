(ns isaac.provider.claude-code.cancel-steps
  (:require
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.provider.claude-code.cancel-support :as cancel]))

(helper! isaac.provider.claude-code.cancel-steps)

(defn setup! []
  (g/assoc! :cancel-fixture (cancel/fixture!)))

(defn- turn! [session]
  (let [turn (cancel/start! (g/get :cancel-fixture) session)]
    (g/assoc-in! [:cancel-turns session] turn)
    turn))

(defn start-and-cancel! [session _delay]
  (let [turn (turn! session)]
    ;; turn! waits for the subprocess marker; acceptance measures a 0.4s running interval before cancel.
    (Thread/sleep 400)
    (let [at (cancel/cancel! turn)]
      (g/assoc-in! [:cancel-ended session] (cancel/ended-within? turn at 3000)))))

(defn start-pair! [a b]
  (turn! a)
  (turn! b))

(defn cancel-session! [session]
  (g/assoc-in! [:cancel-at session] (cancel/cancel! (g/get-in [:cancel-turns session]))))

(defn three-turns-end []
  (g/should= [true true true]
             (mapv (fn [session]
                     (g/get-in [:cancel-ended session]))
                   ["s1" "s2" "s3"])))

(defn turn-ends [session _seconds]
  (g/should (cancel/ended-within? (g/get-in [:cancel-turns session])
                                   (g/get-in [:cancel-at session]) 3000)))

(defn other-still-running [session _seconds]
  (let [running? (cancel/still-running? (g/get-in [:cancel-turns session]) 500)]
    (cancel/cleanup! (g/get :cancel-fixture))
    (g/should running?)))

(defn three-terminations []
  (g/should= 3 (cancel/terminated-count))
  (cancel/cleanup! (g/get :cancel-fixture)))

(defgiven "a Claude Code command that runs for 8 seconds unless killed" isaac.provider.claude-code.cancel-steps/setup!)
(defwhen #"a turn on session \"([^\"]+)\" starts and is cancelled after (0\.4) seconds" isaac.provider.claude-code.cancel-steps/start-and-cancel!)
(defwhen #"turns on sessions \"([^\"]+)\" and \"([^\"]+)\" are running" isaac.provider.claude-code.cancel-steps/start-pair!)
(defwhen #"the turn on session \"([^\"]+)\" is cancelled" isaac.provider.claude-code.cancel-steps/cancel-session!)
(defthen "each of the three turns ends within 3 seconds of its cancel" isaac.provider.claude-code.cancel-steps/three-turns-end)
(defthen "the three cancels log three driver terminations" isaac.provider.claude-code.cancel-steps/three-terminations)
(defthen #"the turn on session \"([^\"]+)\" ends within (3) seconds" isaac.provider.claude-code.cancel-steps/turn-ends)
(defthen #"the turn on session \"([^\"]+)\" is still running (0\.5) seconds later" isaac.provider.claude-code.cancel-steps/other-still-running)
