(ns isaac.llm.mcp-route-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [isaac.llm.mcp-route :as sut]
    [speclj.core :refer :all]))

(describe "Claude MCP turn route"

  (it "contributes POST /claude/turns/:id from the Claude module"
    (let [manifest (edn/read-string (slurp "src/isaac-manifest.edn"))]
      (should= [{:method  :post
                 :path    "/claude/turns/:id"
                 :handler 'isaac.llm.mcp-route/handle}]
               (:isaac.server/route manifest))))

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
  )
