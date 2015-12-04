(ns clj-bson-rpc.json
  "JSON encoding and decoding.
  "
  (:require
    [clj-bson-rpc.bytes :refer [bbuf-concat bbuf-split-after bbuf-split-before]]
    [clojure.core.async :refer [go]]
    [clojure.data.json :as json]
    [manifold.deferred :refer [success-deferred]]
    [manifold.stream :as stream]))

(defn- new-writer-reader-pipe
  "Utility for providing stream reader access for stream parser
   Throws IOException's
  "
  []
  ;; NOTE: PipedInputStream and BufferedReader are left to use
  ;;       their default buffer sizes.
  ;; TODO: Perform some stress testing to verify sufficiency.
  (let [op (java.io.PipedOutputStream.)
        ip (java.io.PipedInputStream. op)
        reader (->> (java.nio.charset.Charset/forName "UTF8")
                    (java.io.InputStreamReader. ip)
                    (java.io.BufferedReader.))]
    [op reader]))

(defn- frameless-decoder
  "Decode JSON messages from manifold stream `s` without framing.
   For details visit:
   http://www.simple-is-better.org/json-rpc/transport_sockets.html
          #pipelined-requests-responses-json-splitter
  "
  [s key-fn]
  (let [source (stream/->source s)
        sink (stream/stream)
        [p-sink reader] (new-writer-reader-pipe)
        decoder (fn []
                  (go
                    (let [continue (atom true)]
                      (try
                        (while @continue
                          (if-let [msg (try
                                         (json/read reader :eof-error? false :key-fn key-fn)
                                         (catch java.io.EOFException e
                                           (ex-info (str e) {:type :trailing-garbage}))
                                         (catch Exception e
                                           (ex-info (str e) {:type :invalid-json})))]
                            (stream/put! sink msg)
                            (reset! continue false)))
                        (catch Exception e)))
                    (stream/close! sink)))
        push-bytes (fn [new-bytes]
                     (try
                       (.write p-sink new-bytes 0 (count new-bytes))
                       (success-deferred true)
                       (catch Exception e
                         (.close p-sink)
                         (success-deferred false))))]
    (decoder)
    (stream/connect-via source push-bytes sink {:downstream? false})
    (stream/on-drained source #(.close p-sink))
    sink))

(defn- rfc-7464-decoder
  "Decode JSON messages from manifold stream `s` using rfc-7464 framing.
   Framing: 0x1E + message + 0x0A (=newline)
   For details visit: https://tools.ietf.org/html/rfc7464
  "
  [s key-fn max-len]
  (let [source (stream/->source s)
        sink (stream/stream)
        err-frame (fn [garbage]
                    (ex-info "Framing error, skipping out-of-frame bytes."
                             {:type :invalid-framing :bytes garbage}))
        err-long (fn [mbytes mlen]
                   (ex-info (str "Message length exceeds the maximum allowed. "
                                 "Max: " max-len ", Message len: " mlen)
                            {:type :exceeds-max-length :bytes mbytes}))
        find-frames (fn [bbuff]
                      (loop [frames [] remainder bbuff]
                        (if (and (some #{0x1e} remainder)
                                 (some #{0x0a} remainder))
                          (if (not= (first remainder) 0x1e)
                            (let [[garbage remainder] (bbuf-split-before 0x1e remainder)]
                              (recur (conj frames (err-frame garbage)) remainder))
                            (let [[frame-bytes remainder] (bbuf-split-after 0x0a remainder)
                                  msg-len (- (count frame-bytes) 2)]
                              (if (> msg-len max-len)
                                (recur (conj frames (err-long frame-bytes msg-len)) remainder)
                                (recur (conj frames (byte-array (butlast (rest frame-bytes)))) remainder))))
                          [frames remainder])))
        decode-frame (fn [frame]
                       (if (instance? clojure.lang.ExceptionInfo frame)
                         frame
                         (try
                           (json/read-str (String. frame "UTF-8") :key-fn key-fn)
                           (catch Exception e
                             (ex-info (str e) {:type :invalid-json :bytes frame})))))
        buffer (atom (byte-array []))
        decoder (fn [new-bytes]
                  (let [[frames remainder] (find-frames (bbuf-concat @buffer new-bytes))
                        trail (if (and (stream/drained? source) (seq remainder))
                                [(ex-info "Non-decodable trailing bytes when stream was closed."
                                          {:type :trailing-garbage :bytes remainder})]
                                [])]
                    (reset! buffer remainder)
                    (->> (into frames trail)
                         (mapv decode-frame)
                         (stream/put-all! sink))))]
    (stream/connect-via source decoder sink {:downstream? false})
    (stream/on-drained source #(do (decoder []) (stream/close! sink)))
    sink))

(defn json-codec
  "JSON codec for a manifold duplex stream.

   * `s` - Duplex stream of raw bytes (possibly from aleph)
   * `:json-framing` - One of following keywords:
       * `:none`
       * `:rfc-7464`
       Defaults to: `:none`
       NOTE: Framing `:none` cannot reliably recover from decoding errors -> user
             should close the stream and not attempt to `take!` further items.
             (This does not apply to `:rfc-7464` framing which recovers automatically.)
   * `:json-key-fn` - Map keys converter function. Default: clojure.core/keyword
   * `:max-len` - Optional argument for limiting incoming message maximum size.
                  Works only with `:rfc-7464`

   Returns a duplex stream which:

    * As a sink takes clojure maps which are encoded and sent to the byte stream `s`
    * As a source produces decoded (clojurized) JSON messages from `s`

   Any non-decodable byte sequences from the raw stream are converted to
   clojure.lang.ExceptionInfo objects which are takeable from the returned
   duplex stream. Thus take! from this source will either:

    * Block waiting a new message.
    * Return a JSON message.
    * Return a clojure.lang.ExceptionInfo object.
      * Message framing error -> `(:type (ex-data error))` is `:invalid-framing`
      * Message decoding error -> type is `:invalid-json`
      * Non-decodable trailing bytes when stream closed-> :type :trailing-garbage
    * Return nil if underlying stream `s` is closed and source is drained.
  "
  [s & {:keys [json-framing json-key-fn max-len]
        :or {json-framing :none
             json-key-fn keyword
             max-len (Integer/MAX_VALUE)}}]
  (let [out (stream/stream)
        encode (fn [m]
                 (.getBytes
                   (json/write-str m)
                   "UTF-8"))
        encoder (case json-framing
                  :none encode
                  :rfc-7464 (fn [m] (byte-array (concat [0x1e] (encode m) [0x0a])))
                  (throw (ex-info "Unsupported framing.")))
        stream-decoder (case json-framing
                         :none     (fn [s] (frameless-decoder s json-key-fn))
                         :rfc-7464 (fn [s] (rfc-7464-decoder s json-key-fn max-len))
                         (throw (ex-info "Unsupported framing.")))]
    (stream/connect (stream/map encoder out) s)
    (stream/splice out (stream-decoder s))))
