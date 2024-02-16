(ns com.vendekagonlabs.datomic-query-service.service-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.client :as http.client]
            [io.pedestal.http :as http]
            [io.pedestal.test :as test]
            [com.vendekagonlabs.datomic-query-service.gzip :as gz]
            [com.vendekagonlabs.datomic-query-service.service :as sut]))

(defonce test-server (atom nil))

(defn as-io-stream [s]
  (io/input-stream (.getBytes s)))

(defn start-test-server []
  (reset! test-server
          (http/start (sut/create-server {:dev true}))))

(defn stop-test-server []
  (http/stop @test-server))

(defn reset-test-server []
  (when @test-server
    (stop-test-server))
  (start-test-server))

(defn api-test
  [& args]
  (apply test/response-for (:io.pedestal.http/service-fn @test-server) args))

(def POST (partial api-test :post))
(def GET (partial api-test :get))

(def test-db-route "/query/unify-example")

(defn decode-body [resp]
  (-> resp :body (json/read-str)))

(defn use-test-server [all-tests]
  (reset-test-server)
  (all-tests)
  (stop-test-server))

(defn wrap-s3-download [response]
  (-> response
      :body
      (http.client/get)
      (deref)
      :body
      (.bytes)
      (gz/gunzip)
      (json/read-str)))

(use-fixtures :once use-test-server)

(defn default-query-headers []
  {"Authorization" (str "Bearer " (System/getenv "BEARER_TOKEN"))
   "Content-Type"  "application/json"})


(deftest health-check
  (testing "Sanity check for health endpoint"
    (is (= 200 (-> "/health" GET :status)))))

(deftest correct-query-behavior
  (testing "Properly formed query requests can:"
    (let [json-resp (POST test-db-route
                          :body (slurp (io/resource "example-q.json"))
                          :headers (merge
                                     (default-query-headers)
                                     {"Accept" "application/json"}))
          s3-resp (POST test-db-route
                        :body (slurp (io/resource "example-q.json"))
                        :headers (merge
                                   (default-query-headers)
                                   {"Accept" "text/plain"}))]
      (testing "return json."
        (is (= 200 (:status json-resp)))
        (is (get (decode-body json-resp) "query_result")))
      (testing "return a pre-cached s3 url (as text)"
        (is (= 200 (:status s3-resp)))
        (is (str/includes? (:body s3-resp) "https"))))
    (testing "encode ISO strings for datetime objects in response."
      (let [dt-resp (POST test-db-route
                          :body (slurp (io/resource "datetime-q.json"))
                          :headers (merge (default-query-headers)
                                          {"Accept" "application/json"}))
            dt-tuples (-> dt-resp decode-body (get "query_result"))
            dts (map first dt-tuples)]
        (is (= 200 (:status dt-resp)))
        (is (every? #(str/starts-with? % "202") dts))
        (is (every? #(str/includes? % "T") dts))))))

(deftest result-serialization
  (testing "Query result serialization"
    (let [resp (POST test-db-route
                     :body (slurp (io/resource "pull-q.json"))
                     :headers (merge
                                (default-query-headers)
                                {"Accept" "application/json"}))
          json-resp (json/read-str (:body resp))
          s3-resp (let [resp (POST test-db-route
                                   :body (slurp (io/resource "pull-q.json"))
                                   :headers (default-query-headers))]
                    (wrap-s3-download resp))]
      (testing "is the same with direct json response and cache response."
        (is (= json-resp s3-resp)))
      (testing "pulls attributes as namespaced keywords prefixed with :"
        (let [kw-val-after-write (-> s3-resp
                                     (get "query_result")
                                     (ffirst)
                                     (get ":sample/id"))]
          (is (some? kw-val-after-write)))))))

(deftest errors
  (testing "Authentication errors"
    (let [resp (POST test-db-route
                     :body (slurp (io/resource "example-q.json"))
                     :headers {"Authorization" "blah"})]
      (testing "should 404"
        (is (= 401 (:status resp))))))
  (testing "Query errors"
    (let [bad-attr-resp (POST test-db-route
                              :body (slurp (io/resource "bad-attr.json"))
                              :headers (default-query-headers))
          no-find-resp (POST test-db-route
                             :body (slurp (io/resource "bad-q.json"))
                             :headers (default-query-headers))]
      (testing "return correct status"
        (is (= 400 (:status bad-attr-resp)))
        (is (= 400 (:status no-find-resp))))
      (testing "and good messages"
        (is (str/includes? (decode-body bad-attr-resp) "Unable to resolve entity"))
        (is (str/includes? (decode-body no-find-resp) ":find clause")))))
  (testing "A database name that's not present"
    (let [bad-db-resp (POST "/query/missing-db"
                            :body (slurp (io/resource "example-q.json"))
                            :headers (default-query-headers))]
      (testing "should 404."
        (is (= 404 (:status bad-db-resp)))))))

(comment
  ;; run all tests in ns
  (in-ns 'com.vendekagonlabs.datomic-query-service.service-test)
  (use 'clojure.test)
  (run-tests *ns*)

  ;; basis-t query still requires some manual inspection to setup and vet
  (reset-test-server)

  (POST test-db-route
        :body (slurp (io/resource "basis-t-q.json"))
        :headers {"Content-Type"  "application/json"
                  "Accept"        "application/json"
                  "Authorization"
                  (str "Bearer " (System/getenv "BEARER_TOKEN"))}))
