# clj-bson-rpc

A Clojure library for implementing BSON RPC services and service clients.


## From json-rpc to bson-rpc

> "JSON-RPC is a stateless, light-weight remote procedure call (RPC) protocol."
[(JSON-RPC 2.0 Specifiation)][1]

However if JSON RPC was desired to be used as a full OSI 7 Application layer
protocol it does fall short in a few specific areas of interest:

* Problems with binary content:
  * JSON type system does not include a binary type. Such a schema may be
    devised in which binaries are piggybacked as JSON strings.
    Besides being ugly there is size penalties:
    * With Base64 encoding: about 33% increase in size.
    * Without encoding: About 100% size increase due to string escape sequeces
      by JSON codec.
  * HTTP implementations of JSON RPC typically place binaries to multipart
    segments but using HTTP usually sacrifices JSON-RPC:s ability for
    bidirectional communication unless websockets are deployed as a transport.
* No datetime type.
* Cumbersome message boundaries. Even if this is somewhat a matter of opinion,
  the fact that json message size cannot be known until the last byte of the
  message is received has the consequence that identifying and processing of
  messages from the input stream is intimately tied to JSON parser itself.

Fortunately [BSON][2] can be used as a drop-in replacement to use in RPC
context and provides a solution to the problem areas mentioned above.
* No penalty for binaries.
* UTC datetime (milliseconds since the Unix epoch.)
* Message length information in the 4 first bytes of the message.


## Dependencies

* Assumes aleph netty library to be used for implementing
  TCP (possibly combining TLS) server and/or client code.
* Alternatively any ring server can be used for using bson-rpc over HTTP.

## Usage

FIXME

## License

Copyright © 2015 Jussi Seppälä

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


[1]: http://www.jsonrpc.org/specification
[2]: http://bsonspec.org/spec.html
