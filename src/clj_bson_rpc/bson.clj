(ns clj-bson-rpc.bson
  "BSON encoding and decoding. Utilizes mongodb codecs for
   clojure <-> bson conversions.
  "
  (:import
    [org.bson BasicBSONDecoder BasicBSONEncoder])
  (:require
    [clj-bson-rpc.bytes :refer [bbuf-concat bbuf-split-at peek-int32-le]]
    [manifold.stream :as stream]
    [somnium.congomongo.coerce :refer [coerce]]))

(defn encode
  "Encode message to BSON byte-array"
  [m]
  (.encode (BasicBSONEncoder.) (coerce m [:clojure :mongo])))

(defn decode
  "Decode BSON byte-array to message"
  [b]
  (coerce (.readObject (BasicBSONDecoder.) b) [:mongo :clojure]))

(defn- stream-decoder
  "Lift BSON message(s) from source stream `s` based on BSON frame recognition,
   decode to clojure (Map messages) and push to the result stream. Various errors will
   produce `ex-info` items to the result stream.
  "
  [s max-len]
  (let [source (stream/->source s)
        sink (stream/stream)
        find-frames (fn [bbuff]
                      (loop [frames [] remainder bbuff]
                        (let [blen (count remainder)
                              rlen (when (>= blen 4) (peek-int32-le remainder))
                              err-long #(ex-info (str "Message length exceeds the maximum allowed. "
                                                      "Max: " max-len ", Message len: " rlen)
                                                 {:type :exceeds-max-length :bytes remainder})
                              err-frame #(ex-info "Message framing error."
                                                  {:type :invalid-framing :bytes remainder})]
                          (cond
                            (nil? rlen)      [frames remainder]
                            (> rlen max-len) [(conj frames (err-long)) remainder]
                            (< rlen 5)       [(conj frames (err-frame)) remainder]
                            (>= blen rlen)   (let [[frame remainder] (bbuf-split-at rlen remainder)]
                                               (recur (conj frames frame) remainder))
                            :else            [frames remainder]))))
        decode-frame (fn [frame]
                       (if (instance? clojure.lang.ExceptionInfo frame)
                         frame
                         (try
                           (decode frame)
                           (catch Exception e
                             (ex-info (str e) {:type :invalid-bson :bytes frame})))))
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

(defn bson-codec
  "BSON codec for a manifold duplex stream.

   * `s` - Duplex stream of raw bytes (possibly from aleph)
   * `max-len` - Optional argument for limiting incoming message maximum size.

   Returns a duplex stream which:

    * As a sink takes clojure maps which are encoded and sent to the byte stream `s`
    * As a source produces decoded (clojurized) BSON messages from `s`

   Any non-decodable byte sequences from the raw stream are converted to
   clojure.lang.ExceptionInfo objects which are takeable from the returned
   duplex stream. Thus take! from this source will either:

    * Block waiting a new message.
    * Return a bson message.
    * Return a clojure.lang.ExceptionInfo object.
      * Message framing error (bson length is negative or < 5)-> :type :invalid-framing
      * Message decoding error from bson parser-> :type :invalid-bson
        ! Stream `s` must be closed.
      * Non-decodable trailing bytes when stream closed-> :type :trailing-garbage
    * Return nil if underlying stream `s` is closed and source is drained.
  "
  [s & {:keys [max-len] :or {max-len (Integer/MAX_VALUE)}}]
  (let [out (stream/stream)]
    (stream/connect (stream/map encode out) s)
    (stream/splice out (stream-decoder s max-len))))
