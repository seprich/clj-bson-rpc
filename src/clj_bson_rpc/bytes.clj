(ns clj-bson-rpc.bytes
  "Utilities for binary/byte-buffer manipulations.
  "
  (:import
    [java.nio ByteBuffer ByteOrder]))

(defn peek-int32-le
  "Read int32-le from byte-array"
  [bbuf]
  (-> (ByteBuffer/wrap bbuf 0 4)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.getInt)
      (long)))

(defn bbuf-concat
  "Concatenate byte buffers.
   byte arrays for now. Consider using ByteBuffer in future (which by itself
   is not dynamic).
  "
  [a b]
  (byte-array (concat a b)))

(defn bbuf-split-at
  [n bbuf]
  (mapv byte-array (split-at n bbuf)))
