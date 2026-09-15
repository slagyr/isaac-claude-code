(ns isaac.llm.mcp-listener
  "Per-turn MCP listener (isaac-ejj3). The process that owns a driven turn
   serves that turn's MCP registry on its own loopback port, so the bridge
   Claude Code spawns always reaches the process holding the turn's tool
   function — a CLI turn or a server turn alike. Each turn mints a nonce
   held only in memory; requests without it are refused before the
   registry is consulted."
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [isaac.logger :as log]
    [isaac.mcp.turns :as mcp-turns]
    [org.httpkit.server :as httpkit])
  (:import
    (java.security MessageDigest SecureRandom)
    (java.util Base64)))

(def ^:private host "127.0.0.1")
(def ^:private nonce-bytes 32)

(defonce ^:private listeners* (atom {}))

(defn- new-nonce []
  (let [bytes (byte-array nonce-bytes)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- utf8 [^String s]
  (.getBytes s "UTF-8"))

(defn- authorized? [nonce request]
  (let [header (get-in request [:headers "authorization"])]
    (and (string? header)
         (MessageDigest/isEqual (utf8 header) (utf8 (str "Bearer " nonce))))))

(defn- read-body [body]
  (cond
    (string? body) body
    (nil? body)    ""
    :else          (slurp (io/reader body))))

(defn- handler [turn-id nonce]
  (fn [request]
    (if (authorized? nonce request)
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (json/generate-string (or (mcp-turns/handle turn-id (read-body (:body request))) {}))}
      (do
        (log/warn :mcp/listener-unauthorized :turn turn-id)
        {:status 401 :body "Unauthorized"}))))

(defn open!
  "Start the listener for `turn-id`. Returns {:turn-id :url :nonce}."
  [turn-id]
  (let [nonce  (new-nonce)
        server (httpkit/run-server (handler turn-id nonce)
                                   {:ip host :port 0 :legacy-return-value? false})
        entry  {:turn-id turn-id
                :url     (str "http://" host ":" (httpkit/server-port server))
                :nonce   nonce
                :server  server}]
    (swap! listeners* assoc turn-id entry)
    (dissoc entry :server)))

(defn lookup [turn-id]
  (some-> (get @listeners* turn-id) (dissoc :server)))

(defn close!
  "Stop the listener for `turn-id`; later connections are refused."
  [turn-id]
  (when-let [{:keys [server]} (get @listeners* turn-id)]
    (swap! listeners* dissoc turn-id)
    @(httpkit/server-stop! server {:timeout 100}))
  nil)

(defn close-all! []
  (doseq [turn-id (keys @listeners*)]
    (close! turn-id)))
