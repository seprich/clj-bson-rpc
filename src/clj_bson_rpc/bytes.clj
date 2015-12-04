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
  "Concatenate byte buffers."
  [a b]
  (if (empty? a)
    b
    (byte-array (concat a b))))

(defn bbuf-split-at
  [n bbuf]
  (mapv byte-array (split-at n bbuf)))

(defn bbuf-split-before
  [bt bbuf]
  (mapv byte-array (split-with (fn [b] (not= b bt)) bbuf)))

(defn bbuf-split-after
  [bt bbuf]
  (let [[fst snd] (split-with (fn [b] (not= b bt)) bbuf)]
    [(byte-array (concat fst [(first snd)])) (byte-array (rest snd))]))
