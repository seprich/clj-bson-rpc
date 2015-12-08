(defproject clj-bson-rpc "0.2.0"
  :description "bson rpc protocol"
  :url "http://github.com/seprich/clj-bson-rpc"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.taoensso/timbre "4.1.4" :exclusions [org.clojure/tools.reader]]
                 [congomongo "0.4.6"]
                 [manifold "0.1.1"]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/data.json "0.2.6"]] ; NOTE: not using cheshire due to issue #94.
  :plugins [[lein-ancient "0.6.8"] ; https://github.com/xsc/lein-ancient/issues/58
            [lein-codox "0.9.0"]
            [lein-kibit "0.1.2"]
            [michaelblume/lein-marginalia "0.9.0"]
            [lein-ns-dep-graph "0.1.0-SNAPSHOT"]]
  :codox {:namespaces [clj-bson-rpc.core]
          :source-uri "https://github.com/seprich/clj-bson-rpc/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}}
  :profiles {:debug {:debug true
                     :injections [(prn (into {} (System/getProperties)))]}
             :dev {}})
