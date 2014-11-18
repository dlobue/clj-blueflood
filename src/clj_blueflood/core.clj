(ns clj-blueflood.core
  (:require [clojure.tools.logging :as log]
            [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            [compojure.route :as route]
            [metrics.core :refer [new-registry]]
            [metrics.ring.instrument :refer [instrument]]
            [metrics.meters :refer [meter mark!]]
            [metrics.timers :refer [timer time!]])

  (:use [ring.middleware.json :only [wrap-json-body]]
        ;[ring.middleware.params :only [wrap-params]]
        ;[ring.middleware.keyword-params :only [wrap-keyword-params]]
        ;[ring.middleware.nested-params :only [wrap-nested-params]]
        [ring.util.response :only [response status]]
        [qbits.alia :as alia]
        [clojure.tools.cli]
        qbits.hayt
        org.httpkit.server)
  (:import
    (java.nio ByteBuffer)
    (com.addthis.metrics.reporter.config ReporterConfig)
    (com.rackspacecloud.blueflood.io.serializers NumericSerializer))
  (:gen-class))


(def registry (new-registry))
(defonce cass-state (atom {}))

(def metrics-inserted (meter registry "metrics-inserted"))

(def insertq-old
  (insert :metrics_full (values [[:key ?] [:column1 ?] [:value (text->blob ?)]])))

(def insertq-full
  (insert :metrics_full (values [[:key ?] [:column1 ?] [:value ?]])))

(def insertq-locator
  (insert :metrics_locator (values [[:key ?] [:column1 ?] [:value ""]]) (if-not-exists)))


(defn metric->blob [m]
    (let [serializer (NumericSerializer/serializerFor (.getClass m))]
        (. serializer (toByteBuffer m))))




(defn cass-execute
  ([query]
   (cass-execute query {}))
  ([query opts]
   (let [opts (merge {:consistency :one} opts)]
     (alia/execute-async (:session @cass-state) query opts))))

(defn cass-prepared-insert-full [metric-name timestamp metric-value]
  (mark! metrics-inserted)
  (cass-execute (:prepared-insert-full @cass-state)
                {:values [metric-name
                          (if (> 2000000000 timestamp)
                            (* 1000 timestamp)
                            timestamp)
                          (if (instance? ByteBuffer metric-value)
                                                  metric-value
                                                  (metric->blob metric-value)) ]}))

(defn cass-prepared-insert-locator [shard metric-name]
  (cass-execute (:prepared-insert-locator @cass-state) {:values [shard metric-name]}))

(defn init-cass
  ([]
   (log/info "init-cass was run with no parameters!")
   (init-cass {}))
  ([options]
   (log/info "Initializing connections to cassandra")
   (let [cluster (alia/cluster {:contact-points (get options :nodes ["localhost"])
                                :port (get options :port 9042)
                                :query-options {:consistency :one}
                                :pooling-options
                                {:core-connections-per-host {:remote 16 :local 16}
                                 :max-connections-per-host {:remote 100 :local 100}
                                 :max-simultaneous-requests-per-connection {:remote 32 :local 32}
                                 :min-simultaneous-requests-per-connection {:remote 16 :local 16}}})
         session (alia/connect cluster)
         client-registry (-> cluster
                             .getMetrics
                             .getRegistry)]

     (. registry (register "datastax" client-registry))
     (alia/execute session (use-keyspace "TESTDATA"))

     (reset! cass-state
             {:cluster cluster
              :session session
              :prepared-insert-locator (alia/prepare session insertq-locator)
              :prepared-insert-full (alia/prepare session insertq-full)}) )))

(defn ingest-processor [datapoints]
  (for [datapoint datapoints
        :let [{:keys [tenantId metricName
                      metricValue collectionTime]} datapoint
              metric-name (str tenantId "." metricName)]
        :when (not (or (instance? String metricValue)
                       (instance? Boolean metricValue)))]
    (cass-prepared-insert-full metric-name collectionTime metricValue)))


(defn solo-ingest-handler [req]
  (log/info "I was asked to handle a request for a single tenant")
  (for [x (doall (ingest-processor
                   (map #(assoc % :tenantId (get-in req [:params :tenant-id])) (:body req) )))]
    @x)
  (response ""))


(defn ingest-handler [req]
  (log/info "OMG A WHALE SHOWED UP!")
  (for [x (doall (ingest-processor (:body req)))]
      @x)
  (response ""))


(defn- json-request? [request]
  (if-let [type (:content-type request)]
    (not (empty? (re-find #"^application/(.+\+)?json" type)))))

(defn wrap-enforce-json-content-type [handler]
  (fn [request]
    (if (json-request? request)
      (handler request)
      (-> (response "Bad request! content-type is not json!")
          (status 400)))))


(defroutes api-routes
  (POST "/echo" [] (fn [req] (response (:body req))))
  (POST "/v1.0/multitenant/experimental/metrics" [] 
        ingest-handler)
        ;(wrap-enforce-json-content-type ingest-handler))
  (POST "/v1.0/:tenant-id/experimental/metrics" [] 
        solo-ingest-handler)
        ;(wrap-enforce-json-content-type solo-ingest-handler))
  (GET "/init-cass" [] (fn [_] (init-cass)))
  (route/not-found "<p>Page not blarg!.</p>")) ;; all other, return 404


(def root-handler
  (-> api-routes
      (instrument registry)
      (wrap-json-body {:keywords? true :bigdecimals? false})))

(defn app [p]
  (log/info "Starting the server - here I go!")
  (init-cass {})
  (log/info "Cassandra has been initialized. Now to give the routes to ring")
  (root-handler p))

(defn -main [& args]
  (let [[options args banner]
        (cli args
             ["-?" "--help" "Show help" :default false :flag true]
             ["-h" "--host" "Interface to listen on." :default "0.0.0.0"]
             ["-p" "--port" "Port to listen on." :default 8080 :parse-fn #(Integer. %)]
             ["-n" "--nodes" "Addresses of cassandra nodes" :default ["localhost"] :parse-fn #(vec (.split % ","))]
             ["-P" "--cass-port" "Cassandra port" :default 9042 :parse-fn #(Integer. %)]
             ["-t" "--threads" "Number of http threads" :default 4 :parse-fn #(Integer. %)]
             ["-q" "--queue-size" "Max number of requests to queue waiting for a thread" :default 204800 :parse-fn #(Integer. %)]
             ["-r" "--reporter-config" "Metrics reporter config location" :default false]
             )]
    (when (:help options)
      (do
        (println banner)
        (System/exit 0)))


    (log/info "Starting the server - here I go!")
    (init-cass {:nodes (:nodes options)
                :port (:cass-port options)})

    (when (:reporter-config options)
      (-> (ReporterConfig/loadFromFileAndValidate (:reporter-config options))
          (.enableAll registry)))

    (log/info "Cassandra has been initialized. Now to give the routes to ring")
    (run-server root-handler {:port (:port options)
                              :thread (:threads options)
                              :queue-size (:queue-size options)
                              :worker-name-prefix "httpkit-"})))

