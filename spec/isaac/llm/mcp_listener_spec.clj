(ns isaac.llm.mcp-listener-spec
  (:require
    [cheshire.core :as json]
    [isaac.llm.mcp-listener :as sut]
    [isaac.mcp.turns :as mcp-turns]
    [org.httpkit.client :as http]
    [speclj.core :refer :all])
  (:import
    (java.net InetAddress)))

(def ^:private tools-list "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")

(defn- post [url nonce]
  @(http/post url {:headers (cond-> {"Content-Type" "application/json"}
                              nonce (assoc "Authorization" (str "Bearer " nonce)))
                   :body    tools-list
                   :as      :text}))

(defn- non-loopback-address []
  (let [address (try (InetAddress/getLocalHost) (catch Exception _ nil))]
    (when (and address (not (.isLoopbackAddress ^InetAddress address)))
      address)))

(defn- port-of [url]
  (parse-long (re-find #"\d+$" url)))

(describe "Per-turn MCP listener (isaac-ejj3)"

  (before
    (mcp-turns/register! "t-harbor" {:session-key "harbor-sess"
                                     :tool-fn     (fn [_name _args] "")
                                     :tools       [{:name "exec__run" :parameters {:type "object"}}]}))

  (after
    (sut/close-all!)
    (mcp-turns/clear-all!))

  (it "listens on the loopback interface at an ephemeral port"
    (let [{:keys [url]} (sut/open! "t-harbor")]
      (should (re-matches #"http://127\.0\.0\.1:\d+" url))
      (should (pos? (port-of url)))))

  (it "is not reachable on a non-loopback interface"
    (if-let [address (non-loopback-address)]
      (let [{:keys [url nonce]} (sut/open! "t-harbor")
            outside             (str "http://" (.getHostAddress ^InetAddress address) ":" (port-of url))]
        (should-not-be-nil (:error (post outside nonce))))
      (pending "no non-loopback IPv4 interface on this host")))

  (it "answers the turn's registry for a request carrying the turn's nonce"
    (let [{:keys [url nonce]} (sut/open! "t-harbor")
          response            (post url nonce)
          body                (json/parse-string (:body response) true)]
      (should= 200 (:status response))
      (should= "exec__run" (get-in body [:result :tools 0 :name]))))

  (it "refuses a wrong or missing nonce with 401 before the registry is consulted"
    (let [{:keys [url]} (sut/open! "t-harbor")
          consulted     (atom false)]
      (with-redefs [mcp-turns/handle (fn [& _] (reset! consulted true) {})]
        (should= 401 (:status (post url "not-the-nonce")))
        (should= 401 (:status (post url nil))))
      (should-not @consulted)))

  (it "mints a distinct, unguessable nonce for each turn"
    (mcp-turns/register! "t-skybeam" {:session-key "s" :tool-fn (fn [_ _] "") :tools []})
    (let [harbor  (:nonce (sut/open! "t-harbor"))
          skybeam (:nonce (sut/open! "t-skybeam"))]
      (should-not= harbor skybeam)
      (should (<= 32 (count harbor)))))

  (it "stops accepting connections once the turn's listener is closed"
    (let [{:keys [url nonce]} (sut/open! "t-harbor")]
      (sut/close! "t-harbor")
      (should-not-be-nil (:error (post url nonce)))
      (should-be-nil (sut/lookup "t-harbor")))))
