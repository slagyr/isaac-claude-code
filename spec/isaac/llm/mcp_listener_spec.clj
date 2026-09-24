(ns isaac.llm.mcp-listener-spec
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.llm.mcp-listener :as sut]
    [isaac.mcp.turns :as mcp-turns]
    [speclj.core :refer :all]))

(defn- post [url nonce message]
  (http/post url {:body    (json/generate-string message)
                  :headers (cond-> {"Content-Type" "application/json"}
                             nonce (assoc "Authorization" (str "Bearer " nonce)))
                  :throw   false}))

(defn- get-request [url nonce]
  (http/get url {:headers (cond-> {}
                             nonce (assoc "Authorization" (str "Bearer " nonce)))
                 :throw   false}))

(describe "per-turn MCP listener"

  (before
    (mcp-turns/clear-all!)
    (sut/stop-all!))

  (after
    (sut/stop-all!)
    (mcp-turns/clear-all!))

  (it "binds loopback and serves the registered turn with its nonce"
    (mcp-turns/register! "t-reef" {:session-key "main"
                                    :tool-fn     (fn [_ _] "ok")
                                    :tools       [{:name "exec__run" :parameters {:type "object"}}]})
    (let [{:keys [url nonce]} (sut/start! "t-reef")
          response (post url nonce {:jsonrpc "2.0" :id 1 :method "tools/list"})]
      (should (re-matches #"http://127\.0\.0\.1:\d+" url))
      (should= 200 (:status response))
      (should= "exec__run" (get-in (json/parse-string (:body response) true) [:result :tools 0 :name]))))

  (it "rejects a missing or incorrect nonce before dispatch"
    (let [called? (atom false)]
      (mcp-turns/register! "t-guard" {:session-key "main"
                                       :tool-fn     (fn [_ _] (reset! called? true))
                                       :tools       []})
      (let [{:keys [url]} (sut/start! "t-guard")
            message {:jsonrpc "2.0" :id 3 :method "tools/call"
                     :params {:name "exec__run" :arguments {:command "echo never"}}}]
        (should= 401 (:status (post url nil message)))
        (should= 401 (:status (post url "wrong" message)))
        (should-not @called?))))

  (it "refuses requests after the listener is stopped"
    (mcp-turns/register! "t-done" {:session-key "main" :tool-fn (constantly "ok") :tools []})
    (let [{:keys [url nonce]} (sut/start! "t-done")]
      (sut/stop! "t-done")
      (should-throw (post url nonce {:jsonrpc "2.0" :id 1 :method "tools/list"}))))

  (it "answers initialize itself, echoing the request's protocolVersion, serverInfo, and tools capability (isaac-mbnb)"
    (mcp-turns/register! "t-init" {:session-key "main" :tool-fn (constantly "ok") :tools []})
    (let [{:keys [url nonce]} (sut/start! "t-init")
          response (post url nonce {:jsonrpc "2.0" :id 1 :method "initialize"
                                    :params  {:protocolVersion "2025-03-26"}})
          body     (json/parse-string (:body response) true)]
      (should= 200 (:status response))
      (should= 1 (:id body))
      (should= "2025-03-26" (get-in body [:result :protocolVersion]))
      (should= "isaac" (get-in body [:result :serverInfo :name]))
      (should= {} (get-in body [:result :capabilities :tools]))))

  (it "answers a notification with 202 and an empty body, never dispatching it as a request (isaac-mbnb)"
    (mcp-turns/register! "t-notify" {:session-key "main" :tool-fn (constantly "ok") :tools []})
    (let [{:keys [url nonce]} (sut/start! "t-notify")
          response (post url nonce {:jsonrpc "2.0" :method "notifications/initialized"})]
      (should= 202 (:status response))
      (should (str/blank? (:body response)))))

  (it "refuses a GET request with 405 (isaac-mbnb)"
    (mcp-turns/register! "t-get" {:session-key "main" :tool-fn (constantly "ok") :tools []})
    (let [{:keys [url nonce]} (sut/start! "t-get")
          response (get-request url nonce)]
      (should= 405 (:status response))))
  )
