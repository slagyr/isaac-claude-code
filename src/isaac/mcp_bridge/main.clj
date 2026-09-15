(ns isaac.mcp-bridge.main
  "The stdio MCP server Claude Code spawns for one driven turn (isaac-ejj3).
   Runs as `bb -cp <classpath> -m isaac.mcp-bridge.main --turn <id> --url
   <listener>` — no isaac launcher. Answers initialize locally, drops
   notifications, and POSTs every other line to the turn's loopback
   listener with the nonce from ISAAC_MCP_NONCE as a bearer token.
   Depends only on babashka built-ins."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http]))

(def PROTOCOL_VERSION "2025-06-18")
(def NONCE_ENV "ISAAC_MCP_NONCE")

(def ^:private PARSE_ERROR -32700)
(def ^:private INVALID_REQUEST -32600)
(def ^:private INTERNAL_ERROR -32603)
(def ^:private UNAUTHORIZED -32001)

(defn- rpc-error [id code message]
  (json/generate-string {:jsonrpc "2.0" :id id :error {:code code :message message}}))

(defn- initialize-result [id]
  (json/generate-string {:jsonrpc "2.0"
                         :id      id
                         :result  {:protocolVersion PROTOCOL_VERSION
                                   :capabilities    {:tools {}}
                                   :serverInfo      {:name "isaac" :version "0.1.0"}}}))

(defn- parse-line [line]
  (try
    (json/parse-string line true)
    (catch Exception _ ::unparseable)))

(defn- forward! [{:keys [url nonce]} id line]
  (let [{:keys [status body error]} @(http/post url {:headers {"Content-Type"  "application/json"
                                                              "Authorization" (str "Bearer " nonce)}
                                                    :body    line
                                                    :as      :text})]
    (cond
      error          (rpc-error id INTERNAL_ERROR (str "turn listener unreachable: " (ex-message error)))
      (= 401 status) (rpc-error id UNAUTHORIZED "unauthorized")
      :else          (str/trim (str body)))))

(defn respond
  "The line to write back for one stdin line, or nil when nothing is written."
  [opts line]
  (let [message (parse-line line)]
    (cond
      (= ::unparseable message)          (rpc-error nil PARSE_ERROR "Parse error")
      (not (map? message))               (rpc-error nil INVALID_REQUEST "Invalid Request")
      (not (contains? message :id))      nil
      (= "initialize" (:method message)) (initialize-result (:id message))
      :else                              (forward! opts (:id message) line))))

(defn parse-args [args]
  (loop [[flag value & more] args
         opts {}]
    (case flag
      nil      opts
      "--turn" (recur more (assoc opts :turn value))
      "--url"  (recur more (assoc opts :url value))
      (recur (cons value more) opts))))

(defn run [opts ^java.io.BufferedReader reader]
  (loop []
    (when-let [line (.readLine reader)]
      (when-not (str/blank? line)
        (when-let [out (respond opts line)]
          (println out)
          (flush)))
      (recur))))

(defn -main [& args]
  (let [{:keys [url] :as opts} (parse-args args)]
    (if (str/blank? url)
      (binding [*out* *err*]
        (println "mcp-bridge: --url is required")
        (System/exit 1))
      (run (assoc opts :nonce (System/getenv NONCE_ENV))
           (java.io.BufferedReader. *in*)))))
