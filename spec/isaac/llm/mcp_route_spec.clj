(ns isaac.llm.mcp-route-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [isaac.llm.mcp-route :as sut]
    [speclj.core :refer :all]))

(describe "Claude MCP turn route"

  (it "contributes POST /claude/turns/:id from the Claude module with scope :mcp"
    (let [manifest (edn/read-string (slurp "src/isaac-manifest.edn"))]
      (should= [{:method  :post
                 :path    "/claude/turns/:id"
                 :handler 'isaac.llm.mcp-route/handle
                 :scope   :mcp}]
               (:isaac.http/route manifest))))

  (it "returns the turn registry result as JSON"
    (with-redefs [sut/handle-turn (fn [turn-id message]
                                    (should= "t-route" turn-id)
                                    (should= "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}" message)
                                    {:jsonrpc "2.0" :id 1 :result {:tools [{:name "exec__run"}]}})]
      (let [response (sut/handle {:route-params {:id "t-route"}
                                  :body         "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"})
            body     (json/parse-string (:body response) true)]
        (should= 200 (:status response))
        (should= "application/json" (get-in response [:headers "Content-Type"]))
        (should= "exec__run" (get-in body [:result :tools 0 :name])))))

  (it "reads a stream body"
    (with-redefs [sut/handle-turn (fn [_turn-id message]
                                    (should= "{\"jsonrpc\":\"2.0\",\"id\":2}" message)
                                    {:jsonrpc "2.0" :id 2 :result {}})]
      (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"jsonrpc\":\"2.0\",\"id\":2}" "UTF-8"))]
        (should= 200 (:status (sut/handle {:route-params {:id "t-stream"} :body body}))))))

  (it "answers initialize itself, echoing the request's protocolVersion (isaac-mbnb)"
    (let [{:keys [status body]} (sut/dispatch "t-init" "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}")
          parsed (json/parse-string body true)]
      (should= 200 status)
      (should= 1 (:id parsed))
      (should= "2025-03-26" (get-in parsed [:result :protocolVersion]))
      (should= "isaac" (get-in parsed [:result :serverInfo :name]))
      (should= {} (get-in parsed [:result :capabilities :tools]))))

  (it "answers a notification with 202 and a nil body, never reaching the registry (isaac-mbnb)"
    (with-redefs [sut/handle-turn (fn [& _] (throw (ex-info "should not dispatch a notification" {})))]
      (let [{:keys [status body]} (sut/dispatch "t-notify" "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")]
        (should= 202 status)
        (should-be-nil body))))
  )
