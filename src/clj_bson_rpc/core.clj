(ns clj-bson-rpc.core
  "JSON-RPC 2.0 and BSON-RPC Connections over
   [manifold](https://github.com/ztellman/manifold) duplex stream.

   Practical applications include using [aleph](https://github.com/ztellman/aleph)
   to establish TCP connection (+ TLS) between the client and the server.

   After an RPC connection has been set on both sides, the RPC nodes are principally equal.
   The TCP client may provide services which the TCP server then uses and vice versa.
  "
  (:require
    [clj-bson-rpc.bson :refer [bson-codec]]
    [clj-bson-rpc.json :refer [json-codec]]
    [clj-bson-rpc.rpc :as rpc :refer [rpc-error rpc-version]]
    [clojure.core.async :refer [<!! go]]
    [manifold.stream :as stream]
    [taoensso.timbre :as log]))

;; ## Default Handlers

(defn- default-timeout-handler
  [{:keys [close! connection-id]}]
  (close!)
  (log/error connection-id "- Connection closed due to idle timeout."))

(defn- default-close-handler
  [{:keys [close! connection-id]}]
  (close!)
  (log/trace connection-id "- Connection closed by peer."))

(defn- default-nil-id-error-handler
  [rpc-ctx msg]
  (log/error (:connection-id rpc-ctx) "- Error response from peer:" (:error msg)))

(defn- default-notification-error-handler
  [rpc-ctx e]
  (log/error (:connection-id rpc-ctx) "- Notification handler failed:" e))

(defn- default-invalid-id-response-handler
  [rpc-ctx msg]
  (log/error (:connection-id rpc-ctx) "- Response handling failed, id:" (:id msg) "is not valid. Msg:" msg))

;; ## Inbound Requests, Notifications and Responses

(defn- handle-request
  [{:keys [close! close-server! connection-id protocol-keyword request-handlers socket]} msg]
  (log/trace connection-id "- Received request:" msg)
  (let [post-action (atom nil)
        ok-response (fn [result] {protocol-keyword rpc-version
                                  :result result
                                  :id (:id msg)})
        server-error (fn [e-msg] {protocol-keyword rpc-version
                                  :error (assoc (:server-error rpc-error) :data e-msg)
                                  :id (:id msg)})
        response (try
                   (rpc/handle-request protocol-keyword request-handlers msg)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= (:type (ex-data e)) :rpc-control)
                       (do (reset! post-action (:action (ex-data e)))
                           (ok-response (:response (ex-data e))))
                       (server-error (str e))))
                   (catch Exception e (server-error (str e))))]
    (if @(stream/put! socket response)
        (log/trace connection-id "- Sent response:" response)
        (do (close!)
            (log/error connection-id "- Failed to send a response to a request."
                       "Services and socket closed. (failed response: " response ")")))
    (case @post-action
      :close (close!)
      :close-server (close-server!)
      :close-all (do (close!) (close-server!))
      nil)))

(defn- handle-notification
  [{:keys [close! close-server! connection-id notification-error-handler notification-handlers] :as rpc-ctx} msg]
  (log/trace connection-id "- Received notification:" msg)
  (let [post-action (atom nil)]
    (try
      (rpc/handle-notification (partial notification-error-handler rpc-ctx) notification-handlers msg)
      (catch clojure.lang.ExceptionInfo e
        (if (= (:type (ex-data e)) :rpc-control)
          (reset! post-action (:action (ex-data e)))
          (notification-error-handler rpc-ctx e)))
      (catch Exception e
        (notification-error-handler rpc-ctx e)))
    (case @post-action
      :close (close!)
      :close-server (close-server!)
      :close-all (do (close!) (close-server!))
      nil)))

(defn- handle-response
  [{:keys [connection-id invalid-id-response-handler response-channels] :as rpc-ctx} msg]
  (log/trace connection-id "- Received response:" msg)
  (let [id (:id msg)
        channel (atom nil)]
    (swap! response-channels (fn [m] (reset! channel (get m id)) (dissoc m id)))
    (if @channel
      (if (not @(stream/put! @channel msg))
        (log/error connection-id "- Sending response failed." msg))
      (invalid-id-response-handler rpc-ctx msg))))

;; ## Inbound Garbage

(defn- handle-parse-error
  [{:keys [close! connection-id protocol-keyword socket] :as rpc-ctx} msg]
  (go
    (let [try-send-error! (fn [] (try
                                   (if (not @(stream/put! socket {protocol-keyword rpc-version
                                                                  :error (assoc (:parse-error rpc-error) :data msg)
                                                                  :id nil}))
                                     (log/error connection-id "- Failed to inform peer about parse error:" msg))
                                   (catch Exception e
                                     (log/error connection-id "- Failed to inform peer about parse error:" e))))
          irrecoverable-error (fn [] (try-send-error!)
                                     (close!)
                                     (log/error connection-id "- Irrecoverable parsing error."
                                                              "Services and socket closed." msg))
          transient-error (fn [] (try-send-error!)
                                 (log/error connection-id "-" msg))]
      (case (:type (ex-data msg))
        :exceeds-max-length (irrecoverable-error)
        :invalid-framing (irrecoverable-error)
        :invalid-json (if (= (:json-framing rpc-ctx) :none) (irrecoverable-error) (transient-error))
        :invalid-bson (transient-error)
        :trailing-garbage (log/warn connection-id "-" msg)
        (log/error connection-id "- Unexpected parse error:" msg)))))

(defn- handle-schema-error
  [{:keys [connection-id protocol-keyword socket]} msg]
  (go
    (let [id (:id msg)]
      (if (contains? msg :method)
        (stream/put! socket {protocol-keyword rpc-version
                             :error (assoc (:invalid-request rpc-error) :data msg)
                             :id id}))  ;; effort made - do not care if socket errors
      (log/error connection-id "- Invalid request:" msg))))

;; ## Service Dispatcher

(defn- run-rpc-services
  "Dispatch Incoming Requests, Notifications and Responses to wrapped handlers."
  [{:keys [async-notification-handling async-request-handling
           connection-closed-handler connection-id idle-timeout idle-timeout-handler
           nil-id-error-handler response-channels run-services socket]
    :as rpc-ctx}]
  (let [take! (if idle-timeout
                (fn [s] (stream/try-take! s nil idle-timeout :timeout))
                (fn [s] (stream/take! s)))]
    (go
      (log/trace connection-id "- Start RPC message dispatcher.")
      (while @run-services
        (let [msg @(take! socket)]
          (log/trace connection-id "- Dispatch message.")
          (cond
            (nil? msg)                                 (connection-closed-handler rpc-ctx)
            (= msg :timeout)                           (idle-timeout-handler rpc-ctx)
            (instance? clojure.lang.ExceptionInfo msg) (handle-parse-error rpc-ctx msg)
            (rpc/request? rpc-ctx msg)                 (if async-request-handling
                                                         (go (handle-request rpc-ctx msg))
                                                         (handle-request rpc-ctx msg))
            (rpc/notification? rpc-ctx msg)            (if async-notification-handling
                                                         (go (handle-notification rpc-ctx msg))
                                                         (handle-notification rpc-ctx msg))
            (rpc/response? rpc-ctx msg)                (handle-response rpc-ctx msg)
            (rpc/nil-id-error-response? rpc-ctx msg)   (nil-id-error-handler rpc-ctx msg)
            :else                                      (handle-schema-error rpc-ctx msg))))
      (doseq [chn (vals @response-channels)] ; Signal to waiters that connection is closed
        (stream/put! chn :closed))
      (log/trace connection-id "- Exit RPC message dispatcher."))))

;; # Public Interface

(def default-options
  "`connect-rpc!` default options. See `connect-bson-rpc!` and `connect-json-rpc!` for semantic explanations.
   These values are used for those options which are not provided in the `options` Map argument.

   Defaults:

   * `:async-notification-handling` - Default value is `false`, which is based on an assumption
                                      that typically notifications are order-sensitive messages e.g.
                                      progress reports which must be handled in the exact order in which
                                      they are received.
   * `:async-request-handling` - Default value is `true`. Async handling allows handlers to execute even
                                 the most time-consuming tasks without blocking other incoming messages.
                                 Yet the client side can deside whether to call requests in tight
                                 sequential order or to call them in parallel.
   * `:connection-closed-handler` - Default handler stops the service for the current stream and
                                    closes the stream. (No effect on the server socket/object.)
   * `:connection-id` - Arbitrary unique ID to identify connection, shown in logging so that
                        multiple connections with separate clients can be traced. nil -> generated integer id.
   * `:id-generator` - Default generator generates 'id-1', 'id-2', etc. ids for outbound requests.
   * `:idle-timeout` - Default value `nil` disables idle timeouts.
   * `:idle-timeout-handler` - Default handler stops the service for the current stream and
                               closes the stream. (No effect on the the server socket/object.)
   * `:invalid-id-response-handler` - Default handler logs the error.
   * `:json-framing` - Default `:none` means JSON messages are streamed consequentially without framing.
   * `:json-key-fn`- Default `clojure.core/keyword`, JSON Object keys are keywordized.
   * `:max-len` - The max capacity defined in bson specification: 2 147 483 647 bytes (Max Int32)
   * `:nil-id-error-handler` - Default handler logs the error message sent by rpc peer node.
   * `:notification-error-handler` - Default handler logs the error.
   * `:server` - Default value `nil`
  "
  {:async-notification-handling false
   :async-request-handling true
   :connection-closed-handler default-close-handler
   :connection-id nil
   :id-generator rpc/default-id-generator
   :idle-timeout nil
   :idle-timeout-handler default-timeout-handler
   :invalid-id-response-handler default-invalid-id-response-handler
   :json-framing :none
   :json-key-fn keyword
   :max-len (Integer/MAX_VALUE)
   :nil-id-error-handler default-nil-id-error-handler
   :notification-error-handler default-notification-error-handler
   :server nil})

(defn close-connection!
  "Call this function within your request or notification handler in order
   to disconnect current tcp connection. `close-connection!` does not return.

   * `response` - Response sent to a request (if within a request handler)
                  just before disconnection. Defaults to nil if not provided.
  "
  ([] (close-connection! nil))
  ([response]
   (throw (ex-info "" {:type :rpc-control :action :close :response response}))))

(defn close-server!
  "Call this function within your request or notification handler in order
   to close server socket. Does not disconnect your current connection.
   `close-server!` does not return.

   * `response` - Response sent to a request (if within a request handler).
                  Defaults to nil if not provided.
  "
  ([] (close-server! nil))
  ([response]
   (throw (ex-info "" {:type :rpc-control :action :close-server :response response}))))

(defn close-connection-and-server!
  "Call this function within your request or notification handler in order
   to close both current connection and the server socket.
   `close-connection-and-server!` does not return.

   * `response` - Response sent to a request (if within a request handler)
                  just before disconnection. Defaults to nil if not provided.
  "
  ([] (close-connection-and-server! nil))
  ([response]
   (throw (ex-info "" {:type :rpc-control :action :close-all :response response}))))

(defn- connect-rpc!
  "Connect BSON/JSON RPC Services.
   See: `connect-bson-rcp!` and `connect-json-rpc!`
  "
  [s codec request-handlers notification-handlers options]
   (let [xson-stream (case codec
                       :bson (bson-codec s :max-len (get options :max-len (Integer/MAX_VALUE)))
                       :json (json-codec s :json-framing (get options :json-framing :none)
                                           :json-key-fn (get options :json-key-fn keyword)
                                           :max-len (get options :max-len (Integer/MAX_VALUE)))
                       (throw (ex-info "Invalid codec.")))
         default-protocol-keyword (keyword (str (name codec) "rpc"))
         run-services (atom true)
         response-channels (atom {})
         rpc-ctx (into (into default-options options)
                       {:close! #(do (stream/close! xson-stream)
                                     (reset! run-services false))
                        :close-server! #(if (:server options) (.close (:server options)))
                        :connection-id (if (nil? (:connection-id options))
                                         (str (swap! rpc/identifier-counter inc))
                                         (:connection-id options))
                        :protocol-keyword (get options :protocol-keyword default-protocol-keyword)
                        :response-channels response-channels
                        :run-services run-services
                        :socket xson-stream})]
     (let [keys->strings (fn [m] (into {} (mapv (fn [[k v]] [(name k) v]) m)))
           notification-handlers (keys->strings
                                   (if (fn? notification-handlers)
                                     (notification-handlers rpc-ctx)
                                     notification-handlers))
           request-handlers (keys->strings
                              (if (fn? request-handlers)
                                (request-handlers rpc-ctx)
                                request-handlers))
           rpc-ctx (assoc rpc-ctx
                          :notification-handlers notification-handlers
                          :request-handlers request-handlers)]
       (run-rpc-services rpc-ctx)
       rpc-ctx)))

(defn connect-bson-rpc!
  "Connect rpc services and create a context for sending BSON-RPC (2.0)
   requests and notifications to the RPC Peer Node over TCP connection.

   * `s` - Manifold duplex stream connected to the rpc peer node.
           (e.g. from aleph.tcp/start-server to the handler or from aleph.tcp/client)
   * `request-handlers`
       * A Map of request handlers: {::String/Keyword ::Function}. These functions are
         exposed to be callable by the rpc peer node. Function return values
         are sent back to peer node and any thrown errors are sent as error responses
         to the peer node.
       * Alternatively this parameter accepts a function which takes `rpc-ctx` and returns
         an above-mentioned Map of request handlers. Necessary if any request handler needs
         to send notifications to peer during the processing of request.
         example function:

           ```
           (defn generate-request-handlers [rpc-ctx]
             {:quick-task quick-task
              :long-process (partial long-process rpc-ctx)})
           ```
         where the `long-process` will thus have the `rpc-ctx` and will be able to call
         `(notify! rpc-ctx :report-progress details)` or even `(request! rpc-ctx ...)`
   * `notification-handlers`
       * A Map of notification handlers: {::String/Keyword ::Function}. These functions
         will receive the named notifications sent by the peer node.
         Any errors thrown by these handlers will be delegated to a callback
         defined by `(:notification-error-handler options)`
       * Alternatively can be a function which takes `rpc-ctx` and returns a Map of handlers.
   * `options` - A Map of optional arguments. `default-options` are used as a baseline of which
                 any or all values can be overridden with ones provided by `options`.

   Valid keys for `options`:

   * `:async-notification-handling` - Boolean for async handling of notifications.
       * false -> Handler functions are guaranteed to be called in the message
                  receiving order. Next incoming message can't be processed until
                  the handler function returns.
       * true -> Handlers executed in go-blocks -> random order.
   * `:async-request-handling` - Boolean for async handling of requests. Async handling allows multiple
     requests to processed in parallel (if client so wishes). Note that client
     can enforce synchronous processing simply by waiting the answer to previous
     request before calling new request.
       * Dispatching of responses is synchronous regardless of this setting.
         If `:async-notification-handling` was set to `false` then all notifications
         possibly sent by a (peer node) response handler will be processed by
         the time the response is returned.
   * `:connection-closed-handler` - Is called when peer closes the connection.
                                    One argument: `rpc-ctx`. Return value ignored.
   * `:connection-id` - ID to use in server logging to identify current connection.
   * `:id-generator` - Is called when a new ID for outgoing rpc request is needed. No arguments.
                       Must return a string or integer which should be unique over the duration of the connection.
   * `:idle-timeout` - Timeout in milliseconds. `idle-timeout-handler` will be triggered if timeout
                       is enabled and nothing has been received from peer node within `idle-timeout`.
                       Disable by setting to `nil`.
   * `:idle-timeout-handler` - One argument: `rpc-ctx`. Return value ignored.
   * `:invalid-id-response-handler` - Two arguments: `rpc-ctx` and message. Return value ignored.
                                      Used if peer sends a response in which ID does not match with
                                      any sent requests waiting for a response.
   * `:max-len` - Incoming message max length.
   * `:nil-id-error-handler` - Is called when an error response with `id: null` is received from the peer node.
                               (Normal error responses are marshalled to throw errors within request! calls.)
                               Two arguments: `rpc-ctx` and `message` (::String). Return value ignored.
   * `:notification-error-handler` - Two arguments: `rpc-ctx` and thrown Exception object. Return value ignored.
   * `:protocol-keyword` - Affects the name of the keyword used in BSON message documents.
                           Defaults to `:bsonrpc` if option is omitted. Having value \"2.0\"
                           Rationale: BSON-RPC is derived from and closely matches JSON-RPC 2.0.
                           (Support for Version 1.0 of the protocol not planned.)
   * `:server` - A java.io.Closeable object. Give if your handlers need the ability to close the
                 server object.

   Returns `rpc-ctx` to be used with `request!` and `notify!`.
  "
  ([s] (connect-rpc! s :bson {} {} default-options))
  ([s options] (connect-rpc! s :bson {} {} options))
  ([s request-handlers notification-handlers]
   (connect-rpc! s :bson request-handlers notification-handlers default-options))
  ([s request-handlers notification-handlers options]
   (connect-rpc! s :bson request-handlers notification-handlers options)))

(defn connect-json-rpc!
  "Connect rpc services and create a context for sending JSON-RPC 2.0
   requests and notifications to the RPC Peer Node over TCP connection.

   * `s` - Manifold duplex stream connected to the rpc peer node.
           (e.g. from aleph.tcp/start-server to the handler or from aleph.tcp/client)
   * `request-handlers`
       * A Map of request handlers: {::String/Keyword ::Function}. These functions are
         exposed to be callable by the rpc peer node. Function return values
         are sent back to peer node and any thrown errors are sent as error responses
         to the peer node.
       * Alternatively this parameter accepts a function which takes `rpc-ctx` and returns
         an above-mentioned Map of request handlers. Necessary if any request handler needs
         to send notifications to peer during the processing of request.
         example function:

           ```
           (defn generate-request-handlers [rpc-ctx]
             {:quick-task quick-task
              :long-process (partial long-process rpc-ctx)})
           ```
         where the `long-process` will thus have the `rpc-ctx` and will be able to call
         `(notify! rpc-ctx :report-progress details)` or even `(request! rpc-ctx ...)`
   * `notification-handlers`
       * A Map of notification handlers: {::String/Keyword ::Function}. These functions
         will receive the named notifications sent by the peer node.
         Any errors thrown by these handlers will be delegated to a callback
         defined by `(:notification-error-handler options)`
       * Alternatively can be a function which takes `rpc-ctx` and returns a Map of handlers.
   * `options` - A Map of optional arguments. `default-options` are used as a baseline of which
                 any or all values can be overridden with ones provided by `options`.

   Valid keys for `options`:

   * `:async-notification-handling` - Boolean for async handling of notifications.
       * false -> Handler functions are guaranteed to be called in the message
                  receiving order. Next incoming message can't be processed until
                  the handler function returns.
       * true -> Handlers executed in go-blocks -> random order.
   * `:async-request-handling` - Boolean for async handling of requests. Async handling allows multiple
     requests to processed in parallel (if client so wishes). Note that client
     can enforce synchronous processing simply by waiting the answer to previous
     request before calling new request.
       * Dispatching of responses is synchronous regardless of this setting.
         If `:async-notification-handling` was set to `false` then all notifications
         possibly sent by a (peer node) response handler will be processed by
         the time the response is returned.
   * `:connection-closed-handler` - Is called when peer closes the connection.
                                    One argument: `rpc-ctx`. Return value ignored.
   * `:connection-id` - ID to use in server logging to identify current connection.
   * `:id-generator` - Is called when a new ID for outgoing rpc request is needed. No arguments.
                       Must return a string or integer which should be unique over the duration of the connection.
   * `:idle-timeout` - Timeout in milliseconds. `idle-timeout-handler` will be triggered if timeout
                       is enabled and nothing has been received from peer node within `idle-timeout`.
                       Disable by setting to `nil`.
   * `:idle-timeout-handler` - One argument: `rpc-ctx`. Return value ignored.
   * `:invalid-id-response-handler` - Two arguments: `rpc-ctx` and message. Return value ignored.
                                      Used if peer sends a response in which ID does not match with
                                      any sent requests waiting for a response.
   * `:json-framing` - One of the following keywords:
       * `:none` - http://www.simple-is-better.org/json-rpc/transport_sockets.html
       * `:rfc-7464` - https://tools.ietf.org/html/rfc7464
   * `:json-key-fn` - JSON Object keys decode converter. Provide custom converter, otherwise by default
                      `clojure.core/keyword` is used. Use `clojure.core/identity` to keep keys as strings.
                      Encoding keywords are always converted to strings.
   * `:max-len` - Incoming message max length. This option is ignored if `:json-framing` is `:none`
   * `:nil-id-error-handler` - Is called when an error response with `id: null` is received from the peer node.
                               (Normal error responses are marshalled to throw errors within request! calls.)
                               Two arguments: `rpc-ctx` and `message` (::String). Return value ignored.
   * `:notification-error-handler` - Two arguments: `rpc-ctx` and thrown Exception object. Return value ignored.
   * `:protocol-keyword` - Affects the name of the keyword used in JSON message documents.
                           Defaults to `:jsonrpc` if option is omitted. Having value \"2.0\" as
                           is required by the JSON-RPC 2.0 Specification.
                           (Support for Version 1.0 of the protocol not planned.)
   * `:server` - A java.io.Closeable object. Give if your handlers need the ability to close the
                 server object.

   Returns `rpc-ctx` to be used with `request!` and `notify!`.
  "
  ([s] (connect-rpc! s :json {} {} default-options))
  ([s options] (connect-rpc! s :json {} {} options))
  ([s request-handlers notification-handlers]
   (connect-rpc! s :json request-handlers notification-handlers default-options))
  ([s request-handlers notification-handlers options]
   (connect-rpc! s :json request-handlers notification-handlers options)))

(defn async-request!
  "RPC Request in a `clojure.core.async` go-block.

   * `rpc-ctx` - The Context from `connect-rpc!`.
   * `method` - Remote method name - a keyword or string.
   * `params` - Parameters for the remote method.

   Returns a channel which will receive:

   * The Result message from the RPC Peer Node or
   * `:closed` or
   * `:send-failure`
  "
  [rpc-ctx method & params]
  (go
    (let [id ((:id-generator rpc-ctx))
          request {(:protocol-keyword rpc-ctx) rpc-version
                   :id id
                   :method (name method)
                   :params params}
          response-timeout (:response-timeout rpc-ctx)
          response-channels (:response-channels rpc-ctx)
          channel (stream/stream)]
      (swap! response-channels (fn [m] (assoc m id (stream/->sink channel))))
      (let [success @(stream/put! (:socket rpc-ctx) request)
            result (if success
                     (if (nil? response-timeout)
                       @(stream/take! (stream/->source channel))
                       @(stream/try-take! (stream/->source channel) nil response-timeout :timeout))
                     (if (stream/closed? (:socket rpc-ctx)) :closed :send-failure))]
        (swap! response-channels (fn [m] (dissoc m id)))
        result))))

(defn async-request-with-timeout!
  "RPC Request in a `clojure.core.async` go-block.

   * `timeout` - Milliseconds to wait for remote method return value.
   * Otherwise identical to `async-request!`

   Returns a channel which will receive results identical to `async-request!` or
   possibly the value `:timeout` if waiting of result timed out.
  "
  [rpc-ctx timeout method & params]
  (apply async-request! (assoc rpc-ctx :response-timeout timeout) method params))

(defn request!
  "RPC Request to the peer node. Waits for a response indefinitely.

   * `rpc-ctx` - Context returned by `connect-rpc!`.
   * `method` - Remote method name - a keyword or string.
   * `params` - Parameters for the remote method.

   Returns: The return value from the remote method or
   Throws:

   * clojure.lang.ExceptionInfo with `ex-data` mappings:
       * {:type :rpc-peer :code <rpc-error-code> :details <details-from-peer>} on peer node errors.
       * {:type :rpc-connection-closed} If either this node or peer node closed the connection.
       * {:type :rpc-buffer-overflow} Send buffer full.
  "
  [rpc-ctx method & params]
  (let [response (<!! (apply async-request! rpc-ctx method params))

        error->ex-info (fn [e]
                         (let [code (get-in e [:error :code])
                               message (get-in e [:error :message])
                               data (get-in e [:error :data])]
                           (ex-info message {:type :rpc-peer :code code :details data})))]
    (cond
      (= response :timeout) (throw (ex-info "Timeout" {:type :rpc-response-timeout}))
      (= response :closed) (throw (ex-info "Connection closed" {:type :rpc-connection-closed}))
      (= response :send-failure) (throw (ex-info "Buffer overflow" {:type :rpc-buffer-overflow}))
      (contains? response :result) (:result response)
      (contains? response :error) (throw (error->ex-info response))
      :else (throw (ex-info "Unknown" {:type :rpc-unknown})))))

(defn request-with-timeout!
  "RPC Request to the peer node. Waits for the response for up to the timeout length of time.

   * `timeout` - Milliseconds to wait for remote method return value.
   * Otherwise identical to `request!`

   Returns: The return value from the remote method or
   Throws: Identically to `request!` or {:type :rpc-response-timeout} when timeouted.
  "
  [rpc-ctx timeout method & params]
  (let [rpc-ctx (assoc rpc-ctx :response-timeout timeout)]
    (apply request! rpc-ctx method params)))

(defn notify!
  "Send RPC Notification to peer. Return boolean success value.

   * `rpc-ctx` - Context returned by `connect-rpc!`
   * `method` - Remote notification handler name - a keyword or string.
   * `params` - Parameters for the notification handler.
  "
  [rpc-ctx method & params]
  (let [notification {(:protocol-keyword rpc-ctx) rpc-version
                      :method (name method)
                      :params params}]
    @(stream/put! (:socket rpc-ctx) notification)))

(defn close!
  "Utility for closing the connection from outside of request handler.
   Use `close-connection!` within response handler.
  "
  [rpc-ctx]
  (stream/close! (:socket rpc-ctx)))
