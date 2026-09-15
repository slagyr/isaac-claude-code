(ns isaac.llm.manifest-spec
  (:require
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(def ^:private manifest (delay (edn/read-string (slurp "src/isaac-manifest.edn"))))

(describe "Claude Code module manifest (isaac-ejj3)"

  (it "contributes the provider template as :claude-code"
    (should= [:claude-code] (keys (:isaac.agent/provider-template @manifest)))
    (should= "claude-cli" (get-in @manifest [:isaac.agent/provider-template :claude-code :template :api])))

  (it "contributes no HTTP route and no isaac CLI command — the bridge runs under bb against a per-turn listener"
    (should-not (contains? @manifest :isaac.http/route))
    (should-not (contains? @manifest :isaac/cli))))
