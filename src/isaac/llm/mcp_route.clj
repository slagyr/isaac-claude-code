(ns isaac.llm.mcp-route
  "HTTP surface for Claude Code's per-turn MCP registry."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]))

(defn handle-turn [turn-id message]
  ((requiring-resolve 'isaac.mcp.turns/handle) turn-id message))

(defn- read-body [body]
  (cond
    (string? body) body
    (nil? body)    ""
    :else          (slurp (io/reader body))))

(defn- notification? [message]
  (and (map? message) (not (contains? message :id))))

(defn- initialize-result [message]
  {:protocolVersion (or (get-in message [:params :protocolVersion]) "2025-06-18")
   :capabilities    {:tools {}}
   :serverInfo      {:name "isaac" :version "0.1.0"}})

(defn dispatch
  "Classify and answer one raw MCP JSON-RPC line for `turn-id`. Returns
   {:status _ :body _} — a nil :body is an empty body (a notification's 202).
   `initialize` is answered here, not by the registry, so protocolVersion can
   echo the request's; every other method (tools/list, tools/call, …) still
   goes through `handle-turn` unchanged — this is the seam the mcp-bridge
   process used to own before the CLI talked HTTP directly (isaac-mbnb)."
  [turn-id body]
  (let [message (try (json/parse-string body true) (catch Exception _ nil))]
    (cond
      (= "initialize" (:method message))
      {:status 200
       :body   (json/generate-string {:jsonrpc "2.0" :id (:id message) :result (initialize-result message)})}

      (notification? message)
      {:status 202 :body nil}

      :else
      {:status 200 :body (json/generate-string (or (handle-turn turn-id body) {}))})))

(defn handle [request]
  (let [turn-id (or (get-in request [:route-params :id])
                    (get-in request [:params :id]))
        {:keys [status body]} (dispatch turn-id (read-body (:body request)))]
    {:status  status
     :headers {"Content-Type" "application/json"}
     :body    (or body "")}))
