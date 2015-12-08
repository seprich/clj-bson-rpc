[![Build Status](https://travis-ci.org/seprich/clj-bson-rpc.svg?branch=master)](https://travis-ci.org/seprich/clj-bson-rpc)
[![Dependencies Status](https://jarkeeper.com/seprich/clj-bson-rpc/status.svg)](https://jarkeeper.com/seprich/clj-bson-rpc)

[![Clojars Project](http://clojars.org/clj-bson-rpc/latest-version.svg)](http://clojars.org/clj-bson-rpc)

# clj-bson-rpc


A Clojure library for JSON-RPC 2.0 and BSON-RPC on TCP (+ TLS).

| [API doc](http://seprich.github.io/clj-bson-rpc/codox/clj-bson-rpc.core.html)
| [Marginalia](http://seprich.github.io/clj-bson-rpc/marginalia.html)
|

## Features

#### Transport

* This library uses [Manifold](https://github.com/ztellman/manifold) message
  streams. Documentation here implicitly assumes that the
  [aleph](https://github.com/ztellman/aleph) library will be used as the
  TCP (+TLS) connectivity provider, but any stream connection which can be
  wrapped into manifold duplex-stream should suffice.

#### JSON-RPC 2.0

* According to [JSON-RPC 2.0 Specifiation](http://www.jsonrpc.org/specification)
* However: [Batches](http://www.jsonrpc.org/specification#batch) are not yet
  supported.
* For the transport level the following message framing options are available:
    * `:none` - This is the default
       [frameless](http://www.simple-is-better.org/json-rpc/transport_sockets.html#pipelined-requests-responses-json-splitter) stream serialization mode.
    * `:rfc-7464` - Message framing according to
      [rfc-7464](https://tools.ietf.org/html/rfc7464) specification.

#### BSON-RPC

* Identical to [JSON-RPC 2.0 Specifiation](http://www.jsonrpc.org/specification)
  with following differences:
    * Messages are encoded as [BSON](http://bsonspec.org/spec.html) instead of
      JSON.
    * Protocol identifier is "bsonrpc" instead of "jsonrpc".
    * Batches are not supported since BSON does not have top-level arrays.
* Benefits over JSON-RPC:
    * Has a Binary type, without message size penalties.
        * JSON-RPC does not have binary type -> Must be carried as strings
          and requires schemas/metadata to custom-handle these fields.
        * Base64 encoded binary within JSON-RPC suffers 33% increase in size.
    * Has a Datetime type.

#### Other

* Logging with [timbre](https://github.com/ptaoussanis/timbre).


## Quickstart

### Minimalistic example
#### TCP Server
```clojure
(ns my.ns
  (:require [aleph.tcp :as tcp]
            [clj-bson-rpc.core :as rpc]))

(def request-handlers
  {:swap-it (fn [msg] (apply str (reverse msg)))
   :intersperse (fn [c msg] (apply str (interpose c (seq msg))))})

(defn connection-handler [s info]
  ;; Start serving requests (in core.async go-block):
  (rpc/connect-json-rpc! s request-handlers {}))

(tcp/start-server connection-handler {:port 4321})
```
On the TCP-server side the client connections are promoted to RPC context with
`connect-json-rpc!` taking socket, request-handlers and an empty Map of notification
handlers. This call forks a core.async process which dispatches all incoming
requests, notifications and responses to their respectful handlers.
`connect-json-rpc!` returns a "context" object which is ignored in this example
server.

#### TCP Client
```clojure
(ns my.ns
  (:require [aleph.tcp :as tcp]
            [clj-bson-rpc.core :as rpc]))

(def rpc-ctx (-> {:host "localhost" :port 4321}
                 @(tcp/client)
                 (rpc/connect-json-rpc!)))

(println (rpc/request! rpc-ctx :swap-it "example"))
; elpmaxe

(println (rpc/request! rpc-ctx :intersperse "--" "example"))
; e--x--a--m--p--l--e
```
On the TCP-client side the connection is also wrapped with
`connect-json-rpc!` but this time we choose to not set any request nor
notification -handlers. The returned rpc-ctx is necessary for making
requests to the Peer Node (= server).

### Full example

#### TCP Server
```clojure
(ns my.ns
  (:require [aleph.tcp :as tcp]
            [clj-bson-rpc.core :as rpc]))

;; Some functions inteded to be exposed for clients to use.

(defn greetings
  [client content]
  (str "Hello" client ":" content))

(defn process
  [rpc-ctx a b c]
  (do
    (println a)
    ;; Send notification back to client during the processing
    (rpc/notify! rpc-ctx :report a)
    (println b)
    ;; Special function for closing connection to peer from
    ;; within request handler:
    (if (= a b) (rpc/close-connection! "Bye!"))
    (rpc/notify! rpc-ctx :report b)
    (println c)
    (rpc/notify! rpc-ctx :report c)
    "Ready"))

;; Set up RPC

(defn requests [client rpc-ctx]
  "client  - info about tcp peer
   rpc-ctx - RPC context to use for callbacks"
  {; When called from client the :greetings takes one argument.
   :greetings (partial greetings client)
   ; When called from client the :process takes 3 arguments.
   :process (partial process rpc-ctx)})

(defn connection-handler [s info]
  (let [requests (partial requests info)
        options {:json-framing :rfc-7464}]
    ; connect-json-rpc! detects that `requests` is a function and
    ; calls it internally with `rpc-ctx` in order to acquire
    ; fully defined interface. (see API doc)
    (rpc/connect-json-rpc! s requests {} options)))

(tcp/start-server connection-handler {:port 4321})
```

#### TCP Client
```clojure
(ns my.ns
  (:require [aleph.tcp :as tcp]
            [clj-bson-rpc.core :as rpc]))

;; Some functions intended to be exposed for server to use.

(defn demo-callback [rpc-ctx x] (+ x 32))

(defn report [item]
  (println "Reporting:" item))

;; Set up RPC

(defn requests [rpc-ctx]
  {:demo-callback (partial demo-callback rpc-ctx)})

(def notifications {:report report})

(def rpc-ctx (-> {:host "localhost" :port 4321}
                 @(tcp/client)
                 (rpc/connect-json-rpc! requests notifications {:json-framing :rfc-7464})))

(println "Finally" (rpc/request! rpc-ctx :process 11 22 33))
; Reporting: 11
; Reporting: 22
; Reporting: 33
; Finally Ready
```

### API - BSON or JSON

* Selection between BSON and JSON boils down to choosing either
  `connect-bson-rpc!` or `connect-json-rpc!`. Option differences
  are minimal and documented in the
  [API doc](http://seprich.github.io/clj-bson-rpc/codox/clj-bson-rpc.core.html).
* Other API functions are used identically for both BSON and JSON.

## License

Copyright © 2015 Jussi Seppälä

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
