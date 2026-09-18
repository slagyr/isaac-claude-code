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

(defn handle [request]
  (let [turn-id (or (get-in request [:route-params :id])
                    (get-in request [:params :id]))
        result  (handle-turn turn-id (read-body (:body request)))]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string (or result {}))}))
