(ns com.vendekagonlabs.datomic-query-service.config)

(defn bearer-token
  []
  (or (System/getenv "BEARER_TOKEN")
      (throw (ex-info "No Bearer Token set in env var BEARER_TOKEN!!"
                      {:config/cause "no bearer token for auth in service env"}))))

(defn db-uri
  []
  (or (System/getenv "BASE_DATOMIC_URI")
      (throw (ex-info "Must set BASE_DATOMIC_URI!"
                      {:config/cause "set BASE_DATOMIC_URI to datomic storage service."}))))

(defn aws-region
  []
  (or (System/getenv "AWS_REGION")
      "us-east-1"))

(defn query-cache-bucket
  []
  (or (System/getenv "QUERY_CACHE_BUCKET")
      "vl-datomic-query-cache"))

(comment
  (db-uri))
