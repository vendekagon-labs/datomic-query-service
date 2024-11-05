(ns com.vendekagonlabs.datomic-query-service.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.interceptor :as i]
            [io.pedestal.http.content-negotiation :refer [negotiate-content]]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [com.vendekagonlabs.datomic-query-service.db :refer [attach-db]]
            [com.vendekagonlabs.datomic-query-service.hash :refer [hash-query]]
            [com.vendekagonlabs.datomic-query-service.db.query
             :as query :refer [parse-query parse-datoms]]
            [com.vendekagonlabs.datomic-query-service.db.query.cache
             :as cache :refer [maybe-hit-cache]]
            [com.vendekagonlabs.datomic-query-service.config :as cfg]
            [com.vendekagonlabs.datomic-query-service.auth :refer [bearer-auth]]
            [io.pedestal.http.route :as route])
  (:import java.time.ZoneId
           java.util.Date
           java.time.format.DateTimeFormatter))

(def cors
  ;; localhost:3000 for dev, but will need to add other URLs potentially depending
  ;; on use, to avoid CORS errors, which are the worst.
  {:allowed-origins ["http://localhost:3000"]
   :creds true})

(defn- inst->str
  "Encode date into string.

  Note: uses java.time functionality, should be thread safe unlike java.util.Date"
  [i]
  (.format (.withZone DateTimeFormatter/ISO_OFFSET_DATE_TIME
                      (ZoneId/systemDefault))
           (.toInstant ^Date i)))

(defn- prep-json-out
  "Encode keywords as ':keyword' in result, and encode instants in standardized
  strings. Other data-type specific coding may be necessary depending on client/
  result consumer context."
  [result-tuples]
  (walk/postwalk
    (fn [v]
      (cond
        (keyword? v) (str v)
        (inst? v) (inst->str v)
        (uuid? v) (str v)
        :else v))
    ;; vec, b/c sets don't play nicely with walk
    (vec result-tuples)))


(defn serialize-q-results
  "Serializes output to JSON

  Note: for large query results/better client architecture, recommend serializing
  results to s3, GCP, etc. rather than JSON in body of request (only used to keep
  demo app simple)"
  [{:keys [result basis-t]}]
  ;; Note encode-kws here happens _before_ json deserialization so that keywords are
  ;; coerced into preferred string representation in Clojure data, then those strings
  ;; are coerced into JSON as string literals.
  (json/write-str
    {"query_result" (prep-json-out result)
     "basis_t" basis-t}))

(defn health
  [_request]
  {:status 200
   :body "ok"})

(defn query*
  [{:keys [request] :as ctx}]
  (let [{:keys [body db]} request
        q-args (assoc body :db db)
        {:keys [error] :as result-map} (query/q->result-or-errors
                                         q-args)]
    (if-not error
      (assoc-in ctx [:request :query-result] result-map)
      (assoc ctx :response
                 {:status  400
                  :headers {"Content-Type" "application/json"}
                  :body    (json/write-str result-map)}))))

(defn datoms-request-error
  [msg]
  {:status 400
   :headers {"Content-Type" "text/plain"}
   :body msg})

(defn ensure-datoms-request
  [ctx]
  (let [body (get-in ctx [:request :body])
        {:keys [index components offset limit]} body]
    (cond (or (not index)
              (not (#{:eavt :aevt :avet :vaet} index)))
          (assoc ctx :response (datoms-request-error
                                 "Must supply index to datoms! One of {:eavt,:aevt :avet :vaet}"))
          ;; or supply your own environment variable here if you want a different limit.
          (> 10000 limit)
          (assoc ctx :response (datoms-request-error
                                 "Server does not support datoms chunk sizes over 10,000"))
          :else
          ctx)))



(defn datoms*
  [{:keys [request] :as ctx}]
  (let [{:keys [body db]} request
        datoms-args (assoc body :db db)
        {:keys [error] :as result-map} (query/datoms->result-or-errors datoms-args)]
    (if-not error
      (assoc-in ctx [:request :datoms-result] result-map)
      (assoc ctx :response
                 {:status 400
                  :headers {"Content-Type" "application/json"}
                  :body (json/write-str result-map)}))))

(def datoms
  (i/interceptor
    {:name ::datoms
     :enter datoms*}))

(def query
  (i/interceptor
    {:name  ::query
     :enter query*}))

(def jsonify-q-results
  (i/interceptor
    {:name  ::jsonify-q-results
     :enter (fn [ctx]
              (let [basis-t (get-in ctx [:request :basis-t])]
                (-> ctx
                    (assoc-in [:request :basis-t] basis-t)
                    (update-in [:request :query-result]
                               serialize-q-results))))}))

(def sometimes-json-response
  (i/interceptor
    {:name  ::maybe-json-response
     :enter (fn [ctx]
              (let [accept-subtype (get-in ctx [:request :accept :subtype])]
                (if (not= "json" accept-subtype)
                  ctx
                  (assoc ctx :response
                             {:status  200
                              :headers {"Content-Type" "application/json"}
                              :body    (get-in ctx [:request :query-result])}))))}))

(def jsonify-datoms
  (i/interceptor
    {:name ::jsonify-datoms
     :enter (fn [ctx]
              (assoc ctx :response
                         {:status 200
                          :headers {"Content-Type" "application/json"}
                          :body (let [result (get-in ctx [:request :datoms-result])]
                                  (-> result prep-json-out))}))}))

(def routes
  (route/expand-routes
    #{["/query/:db-name"
       :post [bearer-auth (negotiate-content ["application/json" "text/plain"]
                                             {:no-match-fn identity})
              parse-query attach-db hash-query
              maybe-hit-cache query jsonify-q-results
              sometimes-json-response cache/write]
       :route-name :query-dbname]
      ["/datoms/:db-name"
       :post [bearer-auth (negotiate-content ["application/json"]
                                             {:no-match-fn identity})
              parse-datoms ensure-datoms-request attach-db datoms jsonify-datoms]]
      ["/health" :get health
       :route-name :health-check]}))

(defn create-server [{:keys [host port dev]}]
  (let [server-port (if port
                      (Integer/parseInt port)
                      80)
        server-host (or host "0.0.0.0")]
    (try
      (cfg/bearer-token)
      (catch Exception e
        (do
          (println "You must set BEARER_TOKEN in the environment
                   in which the service is launched!")
          (throw e))))
    (http/create-server
      {::http/join?  (not dev)
       ::http/host   server-host
       ::http/routes routes
       ::http/type   :jetty
       ::http/allowed-origins cors
       ;; for future modification
       ;; ::http/request-logger
       ::http/port   server-port})))

(defn start [{:keys [host port]}]
  (http/start (create-server {:port port
                              :host host})))

(comment
   (def server
     (start {:port "8988"
             :host "localhost"
             :dev  true})))
