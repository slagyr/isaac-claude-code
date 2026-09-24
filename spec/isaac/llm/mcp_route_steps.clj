(ns isaac.llm.mcp-route-steps
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.null :as null-comm]
    [isaac.config.loader :as loader]
    [isaac.drive.turn :as drive-turn]
    [isaac.llm.mcp-listener :as mcp-listener]
    [isaac.mcp.turns :as mcp-turns]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.step-tables :as match]
    [isaac.tool.names :as names]
    [isaac.tool.registry :as tool-registry]))

(helper! isaac.llm.mcp-route-steps)

(g/after-scenario (fn []
                    (mcp-listener/stop-all!)
                    (mcp-turns/clear-all!)))

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
                                  :tools       (tools-for-allow allowed)})
    (g/update! :mcp-listeners assoc turn-id (mcp-listener/start! turn-id))))

(defn turn-cleared [turn-id]
  (mcp-turns/clear! turn-id))

(defn request-posted [path body]
  (let [id              (last (str/split path #"/"))
        {:keys [url nonce]} (get (g/get :mcp-listeners) id)
        response        (http/post url {:body body
                                                        :headers {"Authorization" (str "Bearer " nonce)
                                                                  "Content-Type" "application/json"}})]
    (g/assoc! :mcp-response (json/parse-string (:body response) true))))

(defn environment-variable-is [name value]
  (g/update! :environment assoc name value))

(defn- parse-body [raw]
  (when (seq (str/trim (str raw)))
    (json/parse-string raw true)))

(defn- post-to-listener! [url bearer body]
  (try
    (let [response (http/post url {:body    body
                                   :headers {"Authorization" bearer
                                             "Content-Type"  "application/json"}
                                   :throw   false})]
      (g/assoc! :mcp-connect-failed? false)
      (g/assoc! :mcp-status (:status response))
      (g/assoc! :mcp-response (parse-body (:body response))))
    (catch Exception _
      (g/assoc! :mcp-connect-failed? true)
      (g/assoc! :mcp-status nil)
      (g/assoc! :mcp-response nil))))

(defn listener-request-posted [turn-id body]
  (let [{:keys [url nonce]} (get (g/get :mcp-listeners) turn-id)]
    (post-to-listener! url (str "Bearer " nonce) body)))

(defn listener-request-posted-with-bearer [turn-id bearer body]
  (let [{:keys [url]} (get (g/get :mcp-listeners) turn-id)]
    (post-to-listener! url bearer body)))

(defn listener-get-requested [turn-id]
  (let [{:keys [url nonce]} (get (g/get :mcp-listeners) turn-id)]
    (try
      (let [response (http/get url {:headers {"Authorization" (str "Bearer " nonce)} :throw false})]
        (g/assoc! :mcp-connect-failed? false)
        (g/assoc! :mcp-status (:status response)))
      (catch Exception _
        (g/assoc! :mcp-connect-failed? true)
        (g/assoc! :mcp-status nil)))))

(defn turn-listener-stopped [turn-id]
  (mcp-listener/stop! turn-id))

(defn mcp-http-status-is [status]
  (g/should= (if (string? status) (parse-long status) status) (g/get :mcp-status)))

(defn mcp-response-body-is-empty []
  (g/should-be-nil (g/get :mcp-response)))

(defn mcp-request-failed-to-connect []
  (g/should (g/get :mcp-connect-failed?)))

(defn response-matches [table]
  (g/should= [] (:failures (match/match-object table (g/get :mcp-response)))))

(defgiven "a turn {turn-id:string} is registered for session {session-key:string}"
  isaac.llm.mcp-route-steps/turn-registered)

(defgiven "the turn {turn-id:string} is cleared"
  isaac.llm.mcp-route-steps/turn-cleared)

(defgiven "environment variable {name:string} is {value:string}"
  isaac.llm.mcp-route-steps/environment-variable-is)

(defgiven "the listener for turn {turn-id:string} is stopped"
  isaac.llm.mcp-route-steps/turn-listener-stopped)

(defwhen "an MCP request is posted to {path:string}:"
  isaac.llm.mcp-route-steps/request-posted)

(defwhen "the listener for turn {turn-id:string} receives an MCP request:"
  isaac.llm.mcp-route-steps/listener-request-posted)

(defwhen "the listener for turn {turn-id:string} receives an MCP request with bearer {bearer:string}:"
  isaac.llm.mcp-route-steps/listener-request-posted-with-bearer)

(defwhen "a GET request is made to the listener for turn {turn-id:string}"
  isaac.llm.mcp-route-steps/listener-get-requested)

(defthen "the MCP response matches:"
  isaac.llm.mcp-route-steps/response-matches)

(defthen "the MCP HTTP status is {int}"
  isaac.llm.mcp-route-steps/mcp-http-status-is)

(defthen "the MCP response body is empty"
  isaac.llm.mcp-route-steps/mcp-response-body-is-empty)

(defthen "the MCP request fails to connect"
  isaac.llm.mcp-route-steps/mcp-request-failed-to-connect)
