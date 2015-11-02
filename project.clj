(defproject clj-bson-rpc "0.1.0-SNAPSHOT"
  :description "bson rpc protocol"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.taoensso/timbre "4.1.4"]
                 [congomongo "0.4.6"]
                 [manifold "0.1.1"]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]]
  :plugins [[lein-ancient "0.6.8"]
            [lein-kibit "0.1.2"]
            [michaelblume/lein-marginalia "0.9.0"]
            [lein-ns-dep-graph "0.1.0-SNAPSHOT"]]
  :profiles {:debug {:debug true
                     :injections [(prn (into {} (System/getProperties)))]}
             :dev {}
             :uberjar {:aot :all}})
