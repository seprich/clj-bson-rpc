# clj-bson-rpc

A Clojure library for implementing BSON RPC services and service clients
on TCP (+ TLS).

-> [API DOC][4]


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

TODO

## License

Copyright © 2015 Jussi Seppälä

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


[1]: http://www.jsonrpc.org/specification
[2]: http://bsonspec.org/spec.html
[3]: https://github.com/ptaoussanis/timbre
[4]: /docs/uberdoc.html
