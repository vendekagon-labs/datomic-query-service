(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.vendekagonlabs/datomic-query-service)
(def version (format "0.8.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis
  (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[com.vendekagonlabs.datomic-query-service.service]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
            :uber-file uber-file
            :basis @basis
            :main 'com.vendekagonlabs.datomic-query-service.service}))

(defn beanstalk [_]
  (uber nil)
  (b/copy-file {:src uber-file
                :target "beanstalk/datomic-query-service.jar"})
  (b/zip {:src-dirs ["beanstalk"]
          :zip-file (format "target/%s-%s.zip" (name lib) version)}))

(comment
  (uber nil)
  (beanstalk nil))
