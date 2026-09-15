(ns isaac.mcp-bridge.main
  "Claude Code stdio MCP server relaying requests to a process-owned listener."
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]))

(def PROTOCOL_VERSION "2025-06-18")

(def option-spec
  [[nil "--turn ID" "Turn id registered in the invoking process"]
   [nil "--url URL" "Per-turn listener URL"]])

(defn- initialize [id]
  {:jsonrpc "2.0"
   :id      id
   :result  {:protocolVersion PROTOCOL_VERSION
             :capabilities    {:tools {}}
             :serverInfo      {:name "isaac" :version "0.1.0"}}})

(defn- notification? [message]
  (and (map? message) (not (contains? message :id))))

(defn- parse-line [line]
  (try
    (json/parse-string line true)
    (catch Exception _
      {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}})))

(defn- unauthorized [id]
  {:jsonrpc "2.0" :id id :error {:code -32001 :message "unauthorized"}})

(defn- relay [{:keys [nonce url]} line message]
  (let [response (http/post url {:body    line
                                 :headers {"Authorization" (str "Bearer " nonce)
                                           "Content-Type"  "application/json"}
                                 :throw   false})]
    (if (= 401 (:status response))
      (json/generate-string (unauthorized (:id message)))
      (:body response))))

(defn- write-line! [line]
  (when-not (str/blank? (str line))
    (println (str/trim-newline line))
    (flush)))

(defn- handle-line! [opts line]
  (let [message (parse-line line)]
    (cond
      (:error message)                  (write-line! (json/generate-string message))
      (= "initialize" (:method message)) (write-line! (json/generate-string (initialize (:id message))))
      (notification? message)           nil
      :else                             (write-line! (relay opts line message)))))

(defn run [opts]
  (let [reader (java.io.BufferedReader. *in*)]
    (loop []
      (when-let [line (.readLine reader)]
        (handle-line! opts line)
        (recur))))
  0)

(defn -main [& args]
  (let [{:keys [options errors]} (tools-cli/parse-opts args option-spec)
        nonce (System/getenv "ISAAC_MCP_NONCE")]
    (cond
      (seq errors)              (do (binding [*out* *err*] (println (str/join "; " errors))) 1)
      (str/blank? (:turn options)) (do (binding [*out* *err*] (println "mcp-bridge: --turn is required")) 1)
      (str/blank? (:url options))  (do (binding [*out* *err*] (println "mcp-bridge: --url is required")) 1)
      (str/blank? nonce)           (do (binding [*out* *err*] (println "mcp-bridge: ISAAC_MCP_NONCE is required")) 1)
      :else                     (run (assoc options :nonce nonce)))))
