(ns com.vendekagonlabs.datomic-query-service.gzip
  (:require [clojure.java.io :as io])
  (:import [java.util.zip GZIPOutputStream GZIPInputStream]
           java.io.ByteArrayInputStream
           java.io.ByteArrayOutputStream))

(defn gzip [txt-content]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [w (-> baos
                      io/output-stream
                      GZIPOutputStream.
                      io/writer)]
      (binding [*out* w]
        (println txt-content)))
    (.toByteArray baos)))

(defn gunzip [bytes]
  (with-open [in (GZIPInputStream.
                   (clojure.java.io/input-stream
                     (ByteArrayInputStream. bytes)))]
    (slurp in)))

