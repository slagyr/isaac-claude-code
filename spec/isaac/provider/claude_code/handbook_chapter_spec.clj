(ns isaac.provider.claude-code.handbook-chapter-spec
  "Lint for isaac-claude-code's own handbook chapter (isaac-7i6t): every
   backtick `config:<dotted.path>` reference (no angle-bracket placeholder
   inside the path) must resolve against the composed config schema, and
   the word right after `isaac ` in every `isaac <command>` invocation
   must name a registered top-level CLI command. Keep both literal and
   real when you write one — this lint fails the build once either drifts
   from what Isaac actually exposes. `<placeholder>` shapes (e.g.
   `config:<dotted.path>` itself, or `<module-id>#<slug>`) are
   intentionally skipped. See isaac.foundation.handbook-chapter-spec /
   isaac.hail.handbook-chapter-spec for the pattern this follows.

   This module's own manifest carries no `:builtin? true` (it is a regular
   third-party module, not part of the foundation/agent core), so
   isaac.foundation.module.discovery/builtin-index alone would never include it — that
   index is filtered to builtin manifests only. Composing a schema that
   actually reflects what this chapter documents (its own contributed
   :env/:forward-env provider-template schema fields,
   merged alongside foundation's and agent's builtin schema) means finding
   this module's own manifest by id and folding it in explicitly, rather
   than declaring it builtin just to satisfy this spec."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.config.schema.resolve :as schema-resolve]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private chapter-resource "isaac/provider/claude_code/handbook.md")
(def ^:private own-module-id :isaac.provider.claude-code)

(defn- chapter-text []
  (some-> (io/resource chapter-resource) slurp))

(defn- own-index-entry
  "This module's own {id -> entry}, found by id across every isaac-manifest.edn
   on the classpath (isaac.foundation.module.discovery/manifest-resource) rather than
   through the builtin-only index."
  []
  (when-let [resource (discovery/manifest-resource own-module-id)]
    (into {} [(discovery/index-entry resource)])))

(defn- module-index []
  (merge (discovery/builtin-index) (own-index-entry)))

(defn- config-refs
  "Backtick `config:<path>` references in `text`, skipping `<placeholder>`
   shapes (any reference whose path still contains an angle bracket)."
  [text]
  (->> (re-seq #"`config:([^`]+)`" text)
       (map second)
       (remove #(str/includes? % "<"))
       distinct))

(defn- cli-commands-mentioned
  "The word immediately following `isaac ` wherever it appears — inline
   code, fenced examples, or plain prose — for every top-level `isaac
   <command>` invocation in `text`."
  [text]
  (->> (re-seq #"isaac\s+([a-zA-Z][a-zA-Z0-9_-]*)" text)
       (map second)
       distinct))

(defn- known-cli-commands
  "Top-level command names contributed to the :isaac/cli berth by every
   module in `index` (builtin plus this module's own entry) — read directly
   off each module's manifest rather than through isaac.foundation.module.berths,
   whose report helpers vary across pinned foundation shas."
  [index]
  (->> (vals index)
       (mapcat (fn [entry] (keys (get-in entry [:manifest :isaac/cli]))))
       (map name)
       set))

(describe "isaac-claude-code handbook chapter (isaac-7i6t)"

  (around [example] (nexus/-with-nexus {:fs (fs/real-fs)} (example)))

  (it "ships at the manifest's declared classpath resource"
    (should-not-be-nil (chapter-text)))

  (it "every `config:<path>` reference resolves against the composed config schema"
    (let [text        (chapter-text)
          root-schema (schema-compose/effective-root-schema (module-index))
          unresolved  (remove #(schema-resolve/schema-for-data-path root-schema %)
                              (config-refs text))]
      (should= [] unresolved)))

  (it "every `isaac <command>` invocation names a registered top-level CLI command"
    (let [text    (chapter-text)
          known   (known-cli-commands (module-index))
          unknown (remove known (cli-commands-mentioned text))]
      (should= [] unknown))))
