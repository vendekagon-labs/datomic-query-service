(ns com.vendekagonlabs.datomic-query-service.auth
  (:require [com.vendekagonlabs.datomic-query-service.config :as cfg]
            [io.pedestal.interceptor :as i]))

(def bearer-auth
  (i/interceptor
    {:name  ::bearer-auth
     :enter (fn [ctx]
              (let [auth-hdr (get-in ctx [:request :headers "authorization"])]
                (if (= auth-hdr (str "Bearer " (cfg/bearer-token)))
                  (assoc-in ctx [:request :auth] true)
                  (assoc ctx :response {:status 401
                                        :body   "Could not authenticate user."
                                        :headers
                                        {"WWW-Authenticate" "Basic realm=\"Restricted Area\""
                                         "ContentType"      "text/plain"}}))))}))
