(ns clj-bson-rpc.bson-rpc-test
  (:require
    [clojure.test :refer :all]
    [clj-bson-rpc.core :refer :all]
    [manifold.stream :as stream]
    [taoensso.timbre :as log]))

;(log/set-level! :trace)

(defn- create-duplex-stream []
  (let [a (stream/stream)
        b (stream/stream)]
    [(stream/splice a b) (stream/splice b a)]))

(def simple-request-handlers
  {:echo (fn [msg] (apply str (reverse msg)))
   :ping (fn [msg] (if (= msg "ping!")
                     "pong!"
                     (throw (ex-info "Value \"ping!\" expected!" {:type :test-error}))))
   :exit (fn [] (close-connection! "ack!"))})

(defn- gen-server-client! []
  (let [[a b] (create-duplex-stream)]
    [(connect-bson-rpc! a simple-request-handlers {}) (connect-bson-rpc! b)]))

(deftest simple-request
  (let [[s c] (gen-server-client!)]
    (is (= (request! c :echo "Hello!") "!olleH"))))

(deftest multiple-requests
  (let [[s c] (gen-server-client!)]
    (is (= (request! c :echo "Hi there!") "!ereht iH"))
    (is (= (request! c :ping "ping!") "pong!"))
    (is (= (request! c :echo "other") "rehto"))))

(deftest request-errors
  (let [[s c] (gen-server-client!)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Method not found"
                          (request! c :tak-ada "blam!")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid params"
                          (request! c :echo "too" "many" "arguments")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Server error"
                          (request! c :ping "implemented error")))))

(deftest client-closes
  (let [[s c] (gen-server-client!)]
    (is (= (request! c :echo "Client closes!") "!sesolc tneilC"))
    (close! c)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Connection closed"
                          (request! c :echo "nono")))))

(deftest server-closes
  (let [[s c] (gen-server-client!)]
    (is (= (request! c :echo "Server closes!") "!sesolc revreS"))
    (is (= (request! c :exit) "ack!"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Connection closed"
                          (request! c :echo "nono")))))


(defn- gen-request-handlers [rpc-ctx]
  {:process (fn [msg]
              (doseq [x msg] (notify! rpc-ctx :note x))
              "Done!")
   :echo (fn [msg] (apply str (reverse msg)))})

(defn- gen-notification-handlers [test-verify rpc-ctx]
  {:note (fn [msg] (swap! test-verify (fn [v] (conj v msg))))})

(deftest requests-and-notifications
  (let [record (atom [])
        [a b] (create-duplex-stream)
        ;; services in server
        s (connect-bson-rpc! a gen-request-handlers {})
        ;; client which accepts notifications from server
        c (connect-bson-rpc! b {} (partial gen-notification-handlers record))]
    (is (= (request! c :process "Whammy!") "Done!"))
    (is (= @record ["W" "h" "a" "m" "m" "y" "!"]))))
