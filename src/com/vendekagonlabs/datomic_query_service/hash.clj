(ns com.vendekagonlabs.datomic-query-service.hash
  (:require [io.pedestal.interceptor :as i]
            [com.vendekagonlabs.value-hash.api :as vh]))

(defn ->md5 [qmap]
  (vh/md5-str qmap))

(defn hash-query*
  [{:keys [request] :as ctx}]
  (let [{:keys [body path-params]} request
        to-hash (merge body path-params)
        query-hash (vh/md5-str to-hash)
        query-s3-key (str query-hash ".json.gz")]
    (assoc-in ctx [:request :cache-key] query-s3-key)))

(def hash-query
  (i/interceptor
    {:name  ::hash-query
     :enter hash-query*}))

(comment
  (query-map->md5 {:query '{:find [?e ?v]
                            :in [$]
                            :where
                            [?e :some/attr ?v]}
                   :db-name "a-db"
                   :args "still-a-db"
                   :basis-t 23})
  (query-map->md5 {:query '{:find [?and ?also]
                            :in [$]
                            :where
                            [[?e :some-other/attr ?v]]}
                   :db-name "yet-another-db"
                   :basis-t 50}))