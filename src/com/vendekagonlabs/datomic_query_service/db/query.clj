(ns com.vendekagonlabs.datomic-query-service.db.query
  (:require [datomic.api :as d]
            [clojure.java.io :as io]
            [io.pedestal.interceptor :as i]
            [org.parkerici.datomic.datalog.json-parser :as datalog-parser]
            [com.vendekagonlabs.datomic-query-service.db :as db]
            [clojure.data.json :as json]))


(defn read-qmap-json
  [input-stream]
  (some-> input-stream
          io/reader
          (slurp)
          (json/read-str)
          ;; only kw-ify top level keys of query map
          ;; i.e., query->:query, args->:args, timeout->:timeout
          ((fn [m]
             (->> (for [[k v] m]
                    [(keyword k) v])
                  (into {}))))))

(defn parse-query*
  [ctx]
  (let [q-map (get-in ctx [:request :body])
        deserialized-q-map (read-qmap-json q-map)
        parsed-body (update deserialized-q-map
                            :query datalog-parser/parse-q)]
    (assoc-in ctx [:request :body] parsed-body)))

(def parse-query
  (i/interceptor
    {:name  ::parse-query
     :enter parse-query*}))

(defn rule-arg-index
  "Returns the position of the rules arg, %, if any, in the query.

  Note: Query grammar is actually a little strange here: % can appear more
  than once but always just as % or does not bind correctly?"
  [{:keys [in]}]
  (let [inds (keep-indexed (fn [ind v]
                             (when (or (= v '%))
                               ind))
                           in)]
    (when (seq inds)
      (first inds))))

(defn q->result-or-errors
  [{:keys [db basis-t query args timeout]}]
  (let [rule-index (rule-arg-index query)
        ;; note: arg parsing can throw
        parsed-args (if-not rule-index
                      args
                      ;; we omit '$ data source in user passed args, so to align with
                      ;; passed args vs. query args we drop 0th index from :in clause
                      (let [adj-ind (dec rule-index)]
                        (concat
                          (take adj-ind args)
                          [(datalog-parser/parse-rules (nth args (dec rule-index)))]
                          (drop (inc adj-ind) args))))
        q-result (try
                   (d/query {:query   query
                             :args    (cons db parsed-args)
                             :timeout (or timeout 10000)})
                   (catch Exception e
                     {:error   (.getMessage e)
                      :ex-info (ex-data e)}))]
    (if (:error q-result)
      q-result
      {:result  q-result
       :basis-t basis-t})))