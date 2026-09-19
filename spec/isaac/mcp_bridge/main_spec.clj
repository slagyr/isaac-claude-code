(ns isaac.mcp-bridge.main-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli.host :as host]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config-api]
    [isaac.fs :as fs]
    [isaac.mcp-bridge.main :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "Claude Code MCP bridge"

  (it "answers initialize locally and drops notifications"
    (let [out (java.io.StringWriter.)]
      (binding [*in*  (java.io.StringReader.
                        (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                             "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"))
                *out* out]
        (should= 0 (sut/run {:turn "t-init" :url "http://127.0.0.1:7000" :nonce "reef"})))
      (let [lines (str/split-lines (str out))]
        (should= 1 (count lines))
        (should= "isaac" (get-in (json/parse-string (first lines) true) [:result :serverInfo :name])))))

  (it "posts requests to the turn listener with the nonce"
    (let [request (atom nil)
          out     (java.io.StringWriter.)]
      (with-redefs [babashka.http-client/post
                    (fn [url opts]
                      (reset! request [url opts])
                      {:status 200 :body "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}"})]
        (binding [*in*  (java.io.StringReader. "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n")
                  *out* out]
          (sut/run {:turn "t-tools" :url "http://127.0.0.1:7001" :nonce "harbor"})))
      (should= "http://127.0.0.1:7001" (first @request))
      (should= "Bearer harbor" (get-in @request [1 :headers "Authorization"]))
      (should= 2 (:id (json/parse-string (str/trim (str out)) true)))))

  (it "turns listener rejection into a JSON-RPC unauthorized error"
    (let [out (java.io.StringWriter.)]
      (with-redefs [babashka.http-client/post (fn [& _] {:status 401 :body "{\"error\":\"unauthorized\"}"})]
        (binding [*in*  (java.io.StringReader. "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"}\n")
                  *out* out]
          (sut/run {:turn "t-guard" :url "http://127.0.0.1:7002" :nonce "wrong"})))
      (let [response (json/parse-string (str/trim (str out)) true)]
        (should= 3 (:id response))
        (should= -32001 (get-in response [:error :code]))
        (should= "unauthorized" (get-in response [:error :message])))))

  (it "declares the mcp-bridge command hosted"
    (let [manifest (edn/read-string (slurp "src/isaac-manifest.edn"))]
      (should= true (get-in manifest [:isaac/cli :mcp-bridge :hosted]))))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "reads stdin from the embedded host and nonce from host env without mutating ambient runtime"
    (registry/register! {:name "mcp-bridge" :hosted true :run-fn sut/run-fn})
    (let [before-nexus (nexus/necho)
          before-memo  (config-api/process-memo-snapshot)
          payload      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
          out          (java.io.StringWriter.)
          err          (java.io.StringWriter.)
          exit         (host/run-embedded {:argv ["mcp-bridge" "--turn" "t-host" "--url" "http://127.0.0.1:7000"]
                                           :in   (java.io.StringReader. payload)
                                           :out  out
                                           :err  err
                                           :env  {"ISAAC_MCP_NONCE" "reef"}
                                           :cwd  "/test/isaac"
                                           :root "/test/isaac"})]
      (should= 0 exit)
      (should= "isaac" (get-in (json/parse-string (str/trim (str out)) true) [:result :serverInfo :name]))
      (should= before-nexus (nexus/necho))
      (should= before-memo (config-api/process-memo-snapshot))))
  )
