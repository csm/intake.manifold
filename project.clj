(defproject com.github.csm/intake "0.1.1-SNAPSHOT"
  :description "Bindings-preserving manifold.deferred operations, core.async compat for manifold"
  :url "https://github.com/csm/intake.manifold"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [manifold "0.1.8"]]
  :profiles {:provided {:dependencies [[org.clojure/core.async "0.4.500"]]}
             :repl {:dependencies [[org.clojure/core.async "0.4.500"]]}
             :test {:dependencies [[org.clojure/core.async "0.4.500"]]}}
  :plugins [[lein-codox "0.10.3"]]
  :codox {:output-path "docs"}
  :repl-options {:init-ns intake.core}
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version"
                   "leiningen.release/bump-version" "release"]
                  ["codox"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])