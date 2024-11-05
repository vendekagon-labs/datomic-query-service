FROM amazoncorretto:21
WORKDIR /app
COPY datomic-query-service.jar /app/datomic-query-service.jar
EXPOSE 8080
CMD ["java", "-Xmx6g", "-cp", "datomic-query-service.jar", "clojure.main", "-m", "com.vendekagonlabs.datomic-query-service.service", "0.0.0.0", "8080"]
