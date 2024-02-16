(ns com.vendekagonlabs.datomic-query-service.s3
  (:require [com.vendekagonlabs.datomic-query-service.config :as cfg]
            [com.vendekagonlabs.datomic-query-service.gzip :as gzip])
  (:import software.amazon.awssdk.services.s3.S3Client
           software.amazon.awssdk.services.s3.presigner.S3Presigner
           software.amazon.awssdk.core.sync.RequestBody
           software.amazon.awssdk.regions.Region
           java.time.Duration
           (software.amazon.awssdk.services.s3.model HeadObjectRequest PutObjectRequest GetObjectRequest)
           software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
           (software.amazon.awssdk.services.s3.presigner.model GetObjectPresignRequest)))

(defn truncate-string
  [s max-len]
  (subs s 0 (min (count s) max-len)))

(defn client
  []
  (.. (S3Client/builder)
      (region (Region/of (cfg/aws-region)))
      (build)))

(defn presigner* []
  (S3Presigner/create))

(def presigner (memoize presigner*))

(defn write!
  "Given query results serialized to json string, writes those to s3 under the
  provided key."
  [s3-key q-result-as-json-str]
  (let [s3-client (client)
        body (gzip/gzip q-result-as-json-str)
        bucket (cfg/query-cache-bucket)
        po-request (.. (PutObjectRequest/builder)
                       (bucket bucket)
                       (key s3-key)
                       (metadata {"ContentType" "application/gzip"})
                       (build))]
    (try
      (.putObject s3-client po-request (RequestBody/fromBytes body))
      (catch Exception e
        (throw
          (ex-info (str "Error, could not write query result to s3-key: " s3-key)
                   {:key s3-key
                    :message (.getMessage e)
                    :query-result-head (-> body (gzip/gunzip)
                                           (truncate-string 5000))}))))))

(defn pre-signed-url
  ([s3-key]
   (pre-signed-url (cfg/query-cache-bucket) s3-key))
  ([s3-bucket s3-key]
   (let [presign-with (presigner)
         get-obj-req (.. (GetObjectRequest/builder)
                         (bucket s3-bucket)
                         (key s3-key)
                         (build))
         minutes 10
         get-presign-req (.. (GetObjectPresignRequest/builder)
                             (signatureDuration (Duration/ofMinutes minutes))
                             (getObjectRequest get-obj-req)
                             (build))
         presig-obj (.presignGetObject presign-with get-presign-req)
         url (.url presig-obj)]
     (.toString url))))

(defn head-object
  ([s3-key]
   (head-object (cfg/query-cache-bucket) s3-key))
  ([s3-bucket s3-key]
   (let [s3-client (client)
         head-obj-req (.. (HeadObjectRequest/builder)
                          (bucket s3-bucket)
                          (key s3-key)
                          (build))]
     (.headObject s3-client head-obj-req))))
