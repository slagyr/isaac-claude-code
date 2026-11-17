(ns isaac.provider.claude-code.claude-cli-cancel-spec
  (:require
    [isaac.provider.claude-code.cancel-support :as cancel]
    [speclj.core :refer [after describe it should should= with]]))

(describe "claude-cli cancel with real subprocesses"
  (with fixture (cancel/fixture!))
  (after (cancel/cleanup! @fixture))

  (it "kills each of three consecutive sessions in one process"
    (let [states (for [session ["s1" "s2" "s3"]]
                   (let [turn (cancel/start! @fixture session)
                         ;; Marker confirms spawn; the acceptance requires a 0.4s running interval before cancel.
                         _    (Thread/sleep 400)
                         at   (cancel/cancel! turn)]
                     (cancel/ended-within? turn at 3000)))]
      (should= [true true true] (vec states))
      (should= 3 (cancel/terminated-count))))

  (it "cancel of a leaves b running"
    (let [a  (cancel/start! @fixture "a")
          b  (cancel/start! @fixture "b")
          at (cancel/cancel! a)]
      (should (cancel/ended-within? a at 3000))
      (should (cancel/still-running? b 500)))))
