(ns isaac.llm.mcp-turn-steps
  (:require
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.null :as null-comm]
    [isaac.config.env :as env]
    [isaac.config.loader :as loader]
    [isaac.drive.turn :as drive-turn]
    [isaac.llm.mcp-listener :as mcp-listener]
    [isaac.mcp.turns :as mcp-turns]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.step-tables :as match]
    [isaac.tool.names :as names]
    [isaac.tool.registry :as tool-registry]))

(helper! isaac.llm.mcp-turn-steps)

(g/after-scenario (fn []
                    (mcp-listener/close-all!)
                    (mcp-turns/clear-all!)))

(def ^:private nonce-env "ISAAC_MCP_NONCE")

(defn- session-store []
  (or (store/registered-store) (nexus/get-in [:sessions :store])))

(defn- allowed-tools []
  (let [cfg (or (loader/snapshot "Claude MCP turn fixture") {})]
    (vec (or (get-in cfg [:crew :main :tools :allow])
             (get-in cfg [:crew "main" :tools :allow])
             []))))

(defn- tools-for-allow [allowed]
  (let [defs    (tool-registry/tool-definitions allowed)
        by-name (into {} (map (juxt :name identity) defs))]
    (mapv (fn [token]
            (let [wire (or (names/wire-name token) (str token))]
              (or (get by-name wire)
                  {:name wire :description wire :parameters {:type "object"}})))
          allowed)))

(defn- drive-tool-fn [session-key allowed]
  (fn [name arguments]
    (#'drive-turn/record-tool-call!
      {:comm          null-comm/channel
       :session-key   session-key
       :allowed-tools allowed
       :module-index  nil
       :caps          {:max-lines (get-in (loader/snapshot "Claude MCP caps") [:tools :defaults :max-lines])
                       :max-bytes (get-in (loader/snapshot "Claude MCP caps") [:tools :defaults :max-bytes])}
       :tool-count    (atom 0)
       :ctx           {:session-store (session-store)}}
      name arguments)))

(defn turn-registered
  "Registers the turn and opens its listener, as the driver does for a driven turn."
  [turn-id session-key]
  (let [allowed (allowed-tools)]
    (mcp-turns/register! turn-id {:session-key session-key
                                  :tool-fn     (drive-tool-fn session-key allowed)
                                  :tools       (tools-for-allow allowed)})
    (mcp-listener/open! turn-id)))

(defn turn-cleared [turn-id]
  (mcp-listener/close! turn-id)
  (mcp-turns/clear! turn-id))

(defn request-posted [path body]
  (let [turn-id (last (str/split path #"/"))]
    (g/assoc! :mcp-response (mcp-turns/handle turn-id body))))

(defn- bridge-env [turn-id]
  (assoc (into {} (System/getenv))
    nonce-env (or (env/env nonce-env) (:nonce (mcp-listener/lookup turn-id)))))

(defn bridge-relays [turn-id stdin]
  (let [{:keys [url]}          (mcp-listener/lookup turn-id)
        argv                   ["bb" "-cp" (System/getProperty "java.class.path")
                                "-m" "isaac.mcp-bridge.main" "--turn" turn-id "--url" url]
        {:keys [out err exit]} @(process/process argv {:in  stdin
                                                       :out :string
                                                       :err :string
                                                       :env (bridge-env turn-id)})
        last-line              (last (remove str/blank? (str/split-lines (str out))))]
    (g/should= 0 exit)
    (g/should-not-be-nil last-line)
    (when-not last-line (println "mcp bridge stderr:" err))
    (g/assoc! :mcp-response (json/parse-string last-line true))))

(defn response-matches [table]
  (g/should= [] (:failures (match/match-object table (g/get :mcp-response)))))

(defgiven "a turn {turn-id:string} is registered for session {session-key:string}"
  isaac.llm.mcp-turn-steps/turn-registered)

(defgiven "the turn {turn-id:string} is cleared"
  isaac.llm.mcp-turn-steps/turn-cleared)

(defwhen "an MCP request is posted to {path:string}:"
  isaac.llm.mcp-turn-steps/request-posted)

(defwhen "the mcp bridge relays for turn {turn-id:string}:"
  isaac.llm.mcp-turn-steps/bridge-relays)

(defthen "the MCP response matches:"
  isaac.llm.mcp-turn-steps/response-matches)
