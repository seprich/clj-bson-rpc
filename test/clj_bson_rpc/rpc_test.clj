(ns clj-bson-rpc.rpc-test
  (:require
    [clojure.test :refer :all]
    [clj-bson-rpc.rpc :refer :all]))

(def test-messages [;; Valid requests
                    {:id "req-1"
                     :bsonrpc "2.0"
                     :method "test-echo"
                     :params [1 2 3]}
                    {:id 22
                     :bsonrpc "2.0"
                     :method "test-echo"
                     :extra {:bar "bara"}}
                    ;; Invalid requests
                    {:id "req-3"
                     :bsonrpc "1.0"
                     :method "test-echo"
                     :params ["foo"]}
                    {:bsonrpc "2.0"
                     :params [1 2 3]}
                    ;; Valid response
                    {:id "resp-1"
                     :bsonrpc "2.0"
                     :result "hello"}
                    ;; Invalid response
                    {:id "resp-2"
                     :bsonrpc "2.0"
                     :result "hello"
                     :error "yello"}
                    ;; Valid error response
                    {:id "eresp-1"
                     :bsonrpc "2.0"
                     :error {:code -32000
                             :message "Server error"
                             :data "Backtrace foo"}}
                    ;; Valid nil id error response
                    {:id nil
                     :bsonrpc "2.0"
                     :error {:code -32700
                             :message "Parse error"}}
                    ;; Valid notifications
                    {:bsonrpc "2.0"
                     :method "notify"
                     :params ["foo"]}
                    {:bsonrpc "2.0"
                     :method "event-X"}
                    ;; Invalid error response
                    {:id "eresp-2"
                     :bsonrpc "2.0"
                     :error {:message "Not enough"}}
                    ;; Garbage messages
                    {}
                    {:id "some"}
                    {:id "other"
                     :jsonrpc "2.0"
                     :result 34}
                    ;;
                    ])

(def ctx  {:protocol-keyword :bsonrpc})

(defn match
  [t-fn expected]
  (doseq [[i msg] (map-indexed vector test-messages)]
    (if (t-fn msg)
      (is (some #{i} expected))
      (is (not (some #{i} expected))))))

(deftest recognize-request
  (let [request? (partial request? ctx)
        expected [0 1]]
    (match request? expected)))

(deftest recognize-notification
  (let [notification? (partial notification? ctx)
        expected [8 9]]
    (match notification? expected)))

(deftest recognize-response
  (let [response? (partial response? ctx)
        expected [4 6]]
    (match response? expected)))

(deftest recognize-nil-id-error-response
  (let [nil-id-error-response? (partial nil-id-error-response? ctx)
        expected [7]]
    (match nil-id-error-response? expected)))

(def notif? (partial notification? ctx))

(deftest notifications-without-handlers
  (let [notifications (filterv notif? test-messages)
        errors (atom [])
        err-handler (fn [e] (swap! errors (fn [v] (conj v e))))]
    (doseq [notification notifications]
      (handle-notification err-handler {} notification))
    (is (= (mapv ex-data @errors) [{:type :notification-handler-not-found :method "notify"}
                                   {:type :notification-handler-not-found :method "event-X"}]))))

(deftest notifications-with-handlers
  (let [notifications (filterv notif? test-messages)
        errors (atom [])
        err-handler (fn [e] (swap! errors (fn [v] (conj v e))))
        calls (atom [])
        handlers {"notify" (fn [par] (swap! calls (fn [v] (conj v (str "(notify " par ")")))))}]
    (doseq [notification notifications]
      (handle-notification err-handler handlers notification))
    (is (= (mapv ex-data @errors) [{:type :notification-handler-not-found :method "event-X"}]))
    (is (= @calls ["(notify foo)"]))))

(def req? (partial request? ctx))

(defn test-echo [a b c] (str "Hello " a ", " b ", " c))

(deftest handle-requests
  (let [requests (filterv req? test-messages)
        handlers {"test-echo" test-echo}
        responses (mapv #(handle-request :bsonrpc handlers %) requests)
        expected [{:bsonrpc "2.0"
                   :id "req-1"
                   :result "Hello 1, 2, 3"}
                  {:bsonrpc "2.0"
                   :id 22
                   :error {:code -32602
                           :message "Invalid params"
                           :data "clojure.lang.ArityException: Wrong number of args (0) passed to: rpc-test/test-echo"}}]]
    (is (= responses expected))))
