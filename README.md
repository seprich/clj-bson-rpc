[![Build Status](https://travis-ci.org/seprich/clj-bson-rpc.svg?branch=master)](https://travis-ci.org/seprich/clj-bson-rpc)
[![Dependencies Status](https://jarkeeper.com/seprich/clj-bson-rpc/status.svg)](https://jarkeeper.com/seprich/clj-bson-rpc)

[![Clojars Project](http://clojars.org/clj-bson-rpc/latest-version.svg)](http://clojars.org/clj-bson-rpc)

# clj-bson-rpc


A Clojure library for implementing BSON RPC services and service clients
on TCP (+ TLS).

-> [API doc](http://seprich.github.io/clj-bson-rpc/codox/clj-bson-rpc.tcp.html)
-> [Marginalia](http://seprich.github.io/clj-bson-rpc/marginalia.html)
## Rationale

#### From JSON-RPC

> "JSON-RPC is a stateless, light-weight remote procedure call (RPC) protocol."
[(JSON-RPC 2.0 Specifiation)][1]

#### To BSON-RPC

However if JSON RPC was desired to be used as a full OSI 7 Application layer
protocol it does fall short in a few specific areas of interest:

* Problems with binary content:
  * JSON type system does not include a binary type. Such a schema may be
    devised in which binaries are piggybacked as JSON strings.
    Besides being ugly there is size penalties:
    * With Base64 encoding: about 33% increase in size.
    * Without encoding: About 100% size increase due to string escape sequeces
      by JSON codec.
  * HTTP implementations of JSON RPC may place binaries to multipart
    segments -> Cannot claim to be pure JSON RPC. (Besides using HTTP
      sacrifices the bi-directionality of JSON RPC.)
* No datetime type.
* Cumbersome message boundaries. Even if this is somewhat a matter of opinion,
  the fact that json message size cannot be known until the last byte of the
  message is received has the consequence that identifying and processing of
  messages from the input stream is intimately tied to JSON parser itself.

Fortunately [BSON][2] can be used almost as a drop-in replacement to use in RPC
context and provides a solution to the problem areas mentioned above.
* No penalty for binaries.
* UTC datetime (milliseconds since the Unix epoch.)
* Message length information in the 4 first bytes of the message.

#### Differences between BSON-RPC and JSON-RPC 2.0:
* Batches are not supported since BSON does not support top-level arrays.
* By default protocol identifier "bsonrpc" is used instead of "jsonrpc".
  Protocol identifier can customized through options.

## Dependencies

* Logging with [timbre][3].

## Quickstart

### Minimalistic example
#### Server
```clojure
(require
  '[aleph.tcp :as tcp]   ; [aleph "0.4.1-beta2"] to project.clj
  '[clj-bson-rpc.tcp :as rpc])

(def request-handlers
  {:swap-it (fn [msg] (apply str (reverse msg)))
   :intersperse (fn [c msg] (apply str (interpose c (seq msg))))})

(defn connection-handler [s info]
  ;; Start serving requests (in core.async go-block):
  (rpc/connect-rpc! s request-handlers {}))

(tcp/start-server connection-handler {:port 4321})
```

#### Client
```clojure
(require
  '[aleph.tcp :as tcp]
  '[clj-bson-rpc.tcp :as rpc])

(def c (rpc/connect-rpc! @(tcp/client {:host "localhost" :port 4321})))

(rpc/request! c :swap-it "example")
; => "elpmaxe"

(rpc/request! c :intersperse "--" "example")
; => "e--x--a--m--p--l--e"
```

## License

Copyright © 2015 Jussi Seppälä

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


[1]: http://www.jsonrpc.org/specification
[2]: http://bsonspec.org/spec.html
[3]: http://github.com/ptaoussanis/timbre
[4]: http://github.com/seprich/
