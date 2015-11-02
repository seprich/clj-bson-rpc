(ns clj-bson-rpc.rpc
  "BSON-RPC Connection over [manifold](https://github.com/ztellman/manifold) duplex stream.

   Practical applications include (but limited to) using [aleph](https://github.com/ztellman/aleph)
   to establish TCP connection (+ TLS) between the client and the server.

   After an RPC connection has been set on both sides, the RPC nodes are principally equal.
   The TCP client may provide services which the TCP server then uses and vice versa.
  "
  )

;; ## Constants and Helpers

(def rpc-error
  {:parse-error      {:code -32700 :message "Parse error"}
   :invalid-request  {:code -32600 :message "Invalid Request"}
   :method-not-found {:code -32601 :message "Method not found"}
   :invalid-params   {:code -32602 :message "Invalid params"}
   :internal-error   {:code -32603 :message "Internal error"}
   :server-error     {:code -32000 :message "Server error"}})

(def rpc-version "2.0")

(defonce identifier-counter (atom 1))
(defn default-id-generator [] (str "id-" (swap! identifier-counter inc)))

;; ## Message Type Identifiers

(defn request?
  [{:keys [protocol-keyword]} msg]
  (and (= (get msg protocol-keyword) rpc-version)
       (string? (:method msg))
       (contains? msg :id) (or (string? (:id msg)) (integer? (:id msg)) (nil? (:id msg)))))

(defn notification?
  [{:keys [protocol-keyword]} msg]
  (and (= (get msg protocol-keyword) rpc-version)
       (string? (:method msg))
       (not (contains? msg :id))))

(defn response?
  [{:keys [protocol-keyword]} msg]
  (let [result-and-no-error (and (contains? msg :result) (not (contains? msg :error)))
        error-and-no-result (and (not (contains? msg :result))
                                 (integer? (get-in msg [:error :code]))
                                 (string? (get-in msg [:error :message])))]
    (and (= (get msg protocol-keyword) rpc-version)
         (contains? msg :id) (or (string? (:id msg)) (integer? (:id msg)))
         (or result-and-no-error error-and-no-result))))

(defn nil-id-error-response?
  [{:keys [protocol-keyword]} msg]
  (let [error-and-no-result (and (not (contains? msg :result))
                                 (integer? (get-in msg [:error :code]))
                                 (string? (get-in msg [:error :message])))]
    (and (= (get msg protocol-keyword) rpc-version)
         (contains? msg :id) (nil? (:id msg))
         error-and-no-result)))

;; ## Inbound Requests, Notifications

(defn handle-request
  [protocol-keyword request-handlers msg]
  (let [method (name (:method msg))
        params (:params msg)
        id (:id msg)
        ok-response (fn [result] {protocol-keyword rpc-version
                                  :result result
                                  :id id})
        invalid-params (fn [e-msg] {protocol-keyword rpc-version
                                    :error (assoc (:invalid-params rpc-error) :data e-msg)
                                    :id id})
        method-not-found (fn [] {protocol-keyword rpc-version
                                 :error (:method-not-found rpc-error)
                                 :id id})]
    (if (contains? request-handlers method)
      (try
        (ok-response (apply (get request-handlers method) params))
        (catch clojure.lang.ArityException e (invalid-params (str e))))
      (method-not-found))))

(defn handle-notification
  [notification-error-handler notification-handlers msg]
  (let [method (name (:method msg))
        params (:params msg)]
    (if (contains? notification-handlers method)
      (apply (get notification-handlers method) params)
      (notification-error-handler
        (ex-info (str "No handler found for notification:" method)
                 {:type :notification-handler-not-found :method method})))))
