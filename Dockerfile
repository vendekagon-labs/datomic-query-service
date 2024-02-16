FROM clojure
COPY src /query-server/src
COPY deps.edn /query-server/deps.edn
COPY util/http-server-docker /query-server/http-server
WORKDIR /query-server
# the step below ensures that git deps work
RUN mkdir -p ~/.ssh && \
    ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts
# resolve deps in container build
RUN clj -Acontainer -P

# query-service env and entrypoint
ENV BEARER_TOKEN="dev" DATOMIC_BASE_URI="datomic:dev://datomic:4334/"
ENTRYPOINT ./http-server
