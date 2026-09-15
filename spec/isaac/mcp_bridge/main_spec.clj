(ns isaac.mcp-bridge.main-spec
  (:require
    [cheshire.core :as json]
    [isaac.mcp-bridge.main :as sut]
    [org.httpkit.server :as httpkit]
    [speclj.core :refer :all]))

(def ^:private received (atom []))
(def ^:private reply (atom {:status 200 :body "{}"}))

(defn- start-listener! []
  (let [server (httpkit/run-server (fn [request]
                                     (swap! received conj {:authorization (get-in request [:headers "authorization"])
                                                           :body          (slurp (:body request))})
                                     @reply)
                                   {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    {:server server
     :url    (str "http://127.0.0.1:" (httpkit/server-port server))}))

(defn- parsed [line]
  (json/parse-string line true))

(describe "mcp bridge (isaac-ejj3)"

  (with-all listener (start-listener!))

  (after-all
    @(httpkit/server-stop! (:server @listener)))

  (before
    (reset! received [])
    (reset! reply {:status 200 :body "{}"}))

  (it "answers initialize itself without contacting the listener"
    (let [out (parsed (sut/respond {:url (:url @listener) :nonce "n"}
                                   "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))]
      (should= 1 (:id out))
      (should= "isaac" (get-in out [:result :serverInfo :name]))
      (should (get-in out [:result :capabilities :tools]))
      (should= [] @received)))

  (it "drops notifications without writing or forwarding anything"
    (should-be-nil (sut/respond {:url (:url @listener) :nonce "n"}
                                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
    (should= [] @received))

  (it "forwards a request line with the nonce as a bearer token and returns the listener's reply"
    (reset! reply {:status 200 :body "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}\n"})
    (let [line "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"
          out  (sut/respond {:url (:url @listener) :nonce "harbor-nonce"} line)]
      (should= "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}" out)
      (should= [{:authorization "Bearer harbor-nonce" :body line}] @received)))

  (it "turns a refused nonce into a JSON-RPC unauthorized error for that request"
    (reset! reply {:status 401 :body "Unauthorized"})
    (let [out (parsed (sut/respond {:url (:url @listener) :nonce "wrong"}
                                   "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}"))]
      (should= 3 (:id out))
      (should= -32001 (get-in out [:error :code]))
      (should= "unauthorized" (get-in out [:error :message]))))

  (it "answers an unparseable line with a JSON-RPC parse error"
    (let [out (parsed (sut/respond {:url (:url @listener) :nonce "n"} "not json"))]
      (should= -32700 (get-in out [:error :code]))
      (should= [] @received)))

  (it "answers with a JSON-RPC error instead of hanging when the listener is gone"
    (let [out (parsed (sut/respond {:url "http://127.0.0.1:1" :nonce "n"}
                                   "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}"))]
      (should= 4 (:id out))
      (should= -32603 (get-in out [:error :code]))))

  (it "reads --turn and --url from its arguments"
    (should= {:turn "t-harbor" :url "http://127.0.0.1:4242"}
             (sut/parse-args ["--turn" "t-harbor" "--url" "http://127.0.0.1:4242"]))))
