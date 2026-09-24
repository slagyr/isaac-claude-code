(ns isaac.llm.mcp-listener
  "Authenticated loopback listener for one process-owned MCP turn. Claude
   Code's HTTP MCP client talks to this directly — no stdio bridge process
   sits between them (isaac-mbnb)."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [isaac.llm.mcp-route :as mcp-route]
    [org.httpkit.server :as httpkit]))

(defonce ^:private listeners* (atom {}))

(defn- request-body [body]
  (cond
    (string? body) body
    (nil? body)    ""
    :else          (slurp (io/reader body))))

(defn- json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string body)})

(defn- handler [turn-id nonce]
  (fn [request]
    (cond
      ;; Streamable HTTP servers may accept a GET for an SSE stream; isaac's
      ;; per-turn listener never streams, so a GET here is simply refused.
      (not= :post (:request-method request))
      (json-response 405 {:error "method not allowed"})

      (not= (str "Bearer " nonce) (get-in request [:headers "authorization"]))
      (json-response 401 {:error "unauthorized"})

      :else
      (let [{:keys [status body]} (mcp-route/dispatch turn-id (request-body (:body request)))]
        {:status  status
         :headers {"Content-Type" "application/json"}
         :body    (or body "")}))))

(defn stop! [turn-id]
  (when-let [server (get-in @listeners* [turn-id :server])]
    (httpkit/server-stop! server)
    (swap! listeners* dissoc turn-id))
  nil)

(defn start! [turn-id]
  (stop! turn-id)
  (let [nonce  (str (java.util.UUID/randomUUID))
        server (httpkit/run-server (handler turn-id nonce)
                                   {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        port   (httpkit/server-port server)
        result {:url (str "http://127.0.0.1:" port) :nonce nonce}]
    (swap! listeners* assoc turn-id (assoc result :server server))
    result))

(defn stop-all! []
  (doseq [turn-id (keys @listeners*)]
    (stop! turn-id))
  nil)
