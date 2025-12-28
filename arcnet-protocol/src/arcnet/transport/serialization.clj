(ns arcnet.transport.serialization
  "Transit+msgpack serialization for ARCNet messages.

   Uses Transit with MessagePack encoding for efficient binary serialization
   while preserving Clojure data types (keywords, UUIDs, dates, etc.)."
  (:require [cognitect.transit :as transit]
            [clojure.tools.logging :as log])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util UUID Date]))

;; =============================================================================
;; Custom Transit Handlers
;; =============================================================================

(def write-handlers
  "Custom Transit write handlers for ARCNet types."
  {;; UUID handling (Transit has built-in but we ensure consistency)
   UUID (transit/write-handler
         "uuid"
         (fn [^UUID uuid] (str uuid)))})

(def read-handlers
  "Custom Transit read handlers for ARCNet types."
  {"uuid" (transit/read-handler
           (fn [s] (UUID/fromString s)))})

;; =============================================================================
;; Serialization Functions
;; =============================================================================

(defn serialize
  "Serializes a Clojure value to Transit+msgpack bytes.

   Returns a byte array suitable for Kafka message values."
  [data]
  (let [baos (ByteArrayOutputStream. 4096)]
    (try
      (let [writer (transit/writer baos :msgpack {:handlers write-handlers})]
        (transit/write writer data)
        (.toByteArray baos))
      (catch Exception e
        (log/error e "Serialization failed" {:data-type (type data)})
        (throw (ex-info "Failed to serialize message"
                        {:type :arcnet/serialization-error
                         :cause (.getMessage e)
                         :data-type (type data)}
                        e))))))

(defn deserialize
  "Deserializes Transit+msgpack bytes to a Clojure value.

   Returns the deserialized data structure."
  [^bytes data]
  (when (and data (pos? (alength data)))
    (let [bais (ByteArrayInputStream. data)]
      (try
        (let [reader (transit/reader bais :msgpack {:handlers read-handlers})]
          (transit/read reader))
        (catch Exception e
          (log/error e "Deserialization failed" {:byte-count (alength data)})
          (throw (ex-info "Failed to deserialize message"
                          {:type :arcnet/deserialization-error
                           :cause (.getMessage e)
                           :byte-count (alength data)}
                          e)))))))

;; =============================================================================
;; Header Serialization (for schema version in Kafka headers)
;; =============================================================================

(defn int->bytes
  "Converts an integer to a 4-byte big-endian byte array."
  [^long n]
  (let [arr (byte-array 4)]
    (aset-byte arr 0 (unchecked-byte (bit-shift-right n 24)))
    (aset-byte arr 1 (unchecked-byte (bit-shift-right n 16)))
    (aset-byte arr 2 (unchecked-byte (bit-shift-right n 8)))
    (aset-byte arr 3 (unchecked-byte n))
    arr))

(defn bytes->int
  "Converts a 4-byte big-endian byte array to an integer."
  [^bytes arr]
  (when (and arr (= 4 (alength arr)))
    (bit-or
     (bit-shift-left (bit-and (aget arr 0) 0xFF) 24)
     (bit-shift-left (bit-and (aget arr 1) 0xFF) 16)
     (bit-shift-left (bit-and (aget arr 2) 0xFF) 8)
     (bit-and (aget arr 3) 0xFF))))

(defn string->bytes
  "Converts a string to UTF-8 bytes."
  [^String s]
  (when s (.getBytes s "UTF-8")))

(defn bytes->string
  "Converts UTF-8 bytes to a string."
  [^bytes arr]
  (when arr (String. arr "UTF-8")))
