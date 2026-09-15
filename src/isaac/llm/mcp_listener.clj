(ns isaac.llm.mcp-listener
  "Authenticated loopback listener for one process-owned MCP turn."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [isaac.mcp.turns :as mcp-turns]
    [org.httpkit.server :as httpkit]))

(defonce ^:private listeners* (atom {}))

(defn- request-body [body]
  (cond
    (string? body) body
    (nil? body)    ""
    :else          (slurp (io/reader body))))

(defn- response [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string body)})

(defn- handler [turn-id nonce]
  (fn [request]
    (if (= (str "Bearer " nonce) (get-in request [:headers "authorization"]))
      (response 200 (or (mcp-turns/handle turn-id (request-body (:body request))) {}))
      (response 401 {:error "unauthorized"}))))

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
