(ns isaac.llm.mcp-route-steps
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.null :as null-comm]
    [isaac.config.loader :as loader]
    [isaac.drive.turn :as drive-turn]
    [isaac.llm.mcp-route :as mcp-route]
    [isaac.mcp.turns :as mcp-turns]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.step-tables :as match]
    [isaac.tool.names :as names]
    [isaac.tool.registry :as tool-registry]))

(helper! isaac.llm.mcp-route-steps)

(g/after-scenario (fn [] (mcp-turns/clear-all!)))

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

(defn turn-registered [turn-id session-key]
  (let [allowed (allowed-tools)]
    (mcp-turns/register! turn-id {:session-key session-key
                                  :tool-fn     (drive-tool-fn session-key allowed)
                                  :tools       (tools-for-allow allowed)})))

(defn turn-cleared [turn-id]
  (mcp-turns/clear! turn-id))

(defn request-posted [path body]
  (let [id       (last (str/split path #"/"))
        response (mcp-route/handle {:route-params {:id id} :body body})]
    (g/assoc! :mcp-response (json/parse-string (:body response) true))))

(defn response-matches [table]
  (g/should= [] (:failures (match/match-object table (g/get :mcp-response)))))

(defgiven "a turn {turn-id:string} is registered for session {session-key:string}"
  isaac.llm.mcp-route-steps/turn-registered)

(defgiven "the turn {turn-id:string} is cleared"
  isaac.llm.mcp-route-steps/turn-cleared)

(defwhen "an MCP request is posted to {path:string}:"
  isaac.llm.mcp-route-steps/request-posted)

(defthen "the MCP response matches:"
  isaac.llm.mcp-route-steps/response-matches)
