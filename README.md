Datomic Query Service
===================

This is a sample Datomic Query service built on pedestal, using the
`datalog-json-parser` library to serialize Datomic queries as plain json.

## Config

To use the query service, set these environment variables:

`BASE_DATOMIC_URI`: the datomic database URI for the system being served by the
query service, without the db-name.

`AWS_REGION`: the aws region s3 and dynamodb etc should use.

`BEARER_TOKEN`: this is a secret/bespoke API key value used for simple request
auth. If you want to productionize this service, you'll need to fork this repo
and write an appropriate auth interceptor to replace the simple bearer token
system.

`QUERY_CACHE_BUCKET`: plain text content returns a pre-signed s3 key, rather
than returning results directly.

For local dev use, eg in the Unify default local system, the docker config only
requires that `BASE_DATOMIC_URI` and `BEARER_TOKEN` be set.

## Running

There are convenience wrappers for running the service with the
[Clojure CLI](https://clojure.org/releases/tools), which they depend on.

`./dev-server` runs a local dev service with a bearer token set to `dev`.
`./http-server` runs a HTTP service as per a deployment (will bind port 80).
