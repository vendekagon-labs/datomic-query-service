(ns com.vendekagonlabs.datomic-query-service.db
  (:require [datomic.api :as d]
            [clojure.string :as str]
            [com.vendekagonlabs.datomic-query-service.config
             :refer [db-uri]]
            [io.pedestal.interceptor :as i]))

(defn db-name->uri
  "Uses `BASE_DATOMIC_URI` from config/env and passed `db-name` to
  construct a connection string. _Note_: not all conn protocols are
  handled, only ddb, dev, and sql have been tested."
  [db-name]
  (let [base-uri (db-uri)
        protocol (second (str/split base-uri #"\:"))]
    (if (= "sql" protocol)
      (str (subs base-uri 0 14)
           db-name
           (subs base-uri 14))
      (str base-uri db-name))))

(defn connect-to
  [db-name]
  (let [uri (db-name->uri db-name)]
    (d/connect uri)))

(defn db-names []
  (d/get-database-names (db-name->uri '*)))

(defn exists? [db-name]
  (let [dbs (set (db-names))]
    (dbs db-name)))

(defn attach-db* [ctx]
  (let [db-name (get-in ctx [:request :path-params :db-name])
        basis-t (get-in ctx [:request :body :basis-t])]
    (try
      (let [conn (connect-to db-name)
            db (d/db conn)]
        (cond-> ctx
                true (assoc-in [:request :db] db)
                basis-t (update-in [:request :db] #(d/as-of % basis-t))
                (nil? basis-t) (assoc-in [:request :body :basis-t] (d/basis-t db))))
      (catch Exception e
        (cond
          (not (exists? db-name))
          (assoc ctx :response {:status  404
                                :headers {"ContentType" "text/plain"}
                                :body    (str "Database " db-name " not found!")})
          :else
          (assoc ctx :response {:status  500
                                :headers {"ContentType" "text/plain"}
                                :body    (.getMessage e)}))))))
(def attach-db
  (i/interceptor
    {:name  ::attach-db
     :enter attach-db*}))
