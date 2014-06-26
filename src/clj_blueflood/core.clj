(ns clj-blueflood.core
  (:require [clojure.tools.logging :as log]
            [compojure.core :refer [defroutes GET POST DELETE ANY context]]
            ;[compojure.handler :as handler]
            [compojure.route :as route])

  (:use [ring.middleware.json :only [wrap-json-body]]
        [ring.middleware.params :only [wrap-params]]
        [ring.middleware.keyword-params :only [wrap-keyword-params]]
        [ring.middleware.nested-params :only [wrap-nested-params]]
        [ring.util.response :only [response status]]
        [qbits.alia :as alia]
        [clojure.tools.cli]
        qbits.hayt
        org.httpkit.server)
  (:gen-class))


(defonce cass-state (atom {}))

(def insertq
  (insert :metrics_full (values [[:key ?] [:column1 ?] [:value (text->blob ?)]])))


(defn cass-execute
  ([query]
   (cass-execute query {}))
  ([query opts]
   (let [opts (merge {:consistency :one} opts)]
     (alia/execute-async (:session @cass-state) query opts))))

(defn cass-prepared-insert [& data]
  (cass-execute (:prepared-insert @cass-state) {:values data}))

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
         session (alia/connect cluster)]

     (alia/execute session (use-keyspace "TESTDATA"))

     (reset! cass-state
             {:cluster cluster
              :session session
              :prepared-insert (alia/prepare session insertq)}) )))

(defn ingest-processor [datapoints]
  (log/info "Processing data is what I do!")
  (for [datapoint datapoints
        :let [{:keys [tenantId metricName
                      metricValue collectionTime]} datapoint
              metric-name (str tenantId "." metricName)]]
    (cass-prepared-insert metric-name collectionTime (str metricValue))))


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
  (POST "/v1.0/multitenant/experimental/metrics" [] 
        (wrap-enforce-json-content-type ingest-handler))
  (POST "/v1.0/:tenant-id/experimental/metrics" [] 
        (wrap-enforce-json-content-type solo-ingest-handler))
  (GET "/init-cass" [] (fn [_] (init-cass)))
  (route/not-found "<p>Page not blarg!.</p>")) ;; all other, return 404


(def root-handler
  (-> api-routes
      wrap-keyword-params
      wrap-nested-params
      wrap-params
      (wrap-json-body {:keywords? true :bigdecimals? true})
      ;(wrap-json-response)
      ))

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
             )]
    (when (:help options)
      (do
        (println banner)
        (System/exit 0)))


    (log/info "Starting the server - here I go!")
    (init-cass {:nodes (:nodes options)
                :port (:cass-port options)})
    (log/info "Cassandra has been initialized. Now to give the routes to ring")
    (run-server root-handler {:port (:port options)
                              :thread 20
                              :worker-name-prefix "httpkit-"})))



