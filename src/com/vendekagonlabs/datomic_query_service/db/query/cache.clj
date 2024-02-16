(ns com.vendekagonlabs.datomic-query-service.db.query.cache
  (:require [com.vendekagonlabs.datomic-query-service.s3 :as s3]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as i]))


(defn cached?* [cache-key]
  ;; s3 head object fails if it doesn't exist, here we smash
  ;; exception flow into condition flow
  (try
    (s3/head-object cache-key)
    (log/info :query-cache/hit cache-key)
    true
    (catch Exception e
      (log/info :query-cache/miss cache-key
                :s3/message (.getMessage e))
      false)))

(def cached? (memoize cached?*))

(defn maybe-hit-cache*
  [ctx]
  (let [cache-key (get-in ctx [:request :cache-key])
        accept-subtype (get-in ctx [:request :accept :subtype])
        refresh-cache? (get-in ctx [:request :body :refresh-cache])]
    (if (and (= "plain" accept-subtype)
             (not refresh-cache?)
             (cached? cache-key))
      (do
        (log/info :query-cache/response cache-key)
        (assoc ctx :response {:status 200
                              :body   (s3/pre-signed-url cache-key)
                              :headers {"Content-Type" "text/plain"}}))
      ctx)))

(def maybe-hit-cache
  (i/interceptor
    {:name  ::maybe-hit-cache
     :enter maybe-hit-cache*}))

(defn write* [ctx]
  (let [cache-key (get-in ctx [:request :cache-key])
        q-result (get-in ctx [:request :query-result])
        _ (s3/write! cache-key q-result)
        pre-signed-url (s3/pre-signed-url cache-key)]
    (log/info :query-cache/write cache-key)
    (assoc ctx :response
               {:status  200
                :body    pre-signed-url
                :headers {"Content-Type" "text/plain"}})))

(def write
  (i/interceptor
    {:name  ::write
     :enter write*}))
