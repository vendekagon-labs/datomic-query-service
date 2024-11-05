(ns com.vendekagonlabs.datomic-query-service.db.query
  (:require [datomic.api :as d]
            [clojure.java.io :as io]
            [io.pedestal.interceptor :as i]
            [org.parkerici.datomic.datalog.json-parser :as datalog-parser]
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

(defn ref-attr? [db attr-eid]
  (= :db.type/ref
     (-> (d/attribute db attr-eid)
         (:value-type))))

(defn ->ident [db eid]
  (when-let [ident (:db/ident (d/entity db eid))]
    ident))

(defn maybe->ident [db attr-eid val]
  (if-not (ref-attr? db attr-eid)
    val
    (or (->ident db val) val)))

(defn hydrate-datom
  "Given a Datomic Datom object, uses nth destructuring to pull it apart and transform
  it into a map with :e :a :v and :tx keys, looking up idents when suitable for resolving
  attribute names or ident enums."
  [db [e a v tx]]
  {:e e
   :a (->ident db a)
   :v (maybe->ident db a v)
   :tx tx})

(defn datoms->result-or-errors
  [{:keys [db basis-t index components seek limit offset]}]
  (let [offset* (or offset 0)
        limit* (or limit 1000)
        datoms-fn (if seek
                    (partial d/seek-datoms db index)
                    (partial d/datoms db index))
        result (try
                 (->> (apply datoms-fn components)
                      (drop offset*)
                      (take limit*)
                      (mapv (partial hydrate-datom db)))
                 (catch Exception e
                   {:error (.getMessage e)
                    :ex-info (ex-data e)}))]
    (if (:error result)
      result
      {:result result
       :basis-t basis-t})))

(defn q->result-or-errors
  "Returns the result of a query or -- in the case of errors --
  a map which contains the error message and any reported ex-data
  in the :error and :ex-info keys respectively."
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

(comment
  (require '[com.vendekagonlabs.datomic-query-service.db :as db])
  (db/db-names)
  (def dev-conn (db/connect-to "template-db"))
  (def dev-db (d/db dev-conn))
  (datoms->result-or-errors {:db dev-db
                             :basis-t 1
                             :index :aevt
                             :components
                             [:sample/subject]
                             :seek true
                             :limit 100
                             :offset 100}))