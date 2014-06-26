(defproject clj-blueflood "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.cli "0.2.1"]
                 ;[com.rackspacecloud/blueflood-core "2.0.0-SNAPSHOT"]
                 [cc.qbits/hayt "2.0.0-beta4"]
                 ;[cc.qbits/knit "0.2.1"]
                 [cc.qbits/alia "2.0.0-rc3"]
                 [org.xerial.snappy/snappy-java "1.0.5"]
                 [http-kit "2.1.16"]
                 [ring/ring-json "0.3.1"]
                 [compojure "1.1.8"]
                 [com.google.protobuf/protobuf-java "2.5.0"]
                 ;[bigml/histogram "3.2.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.7"]
                 [log4j/log4j "1.2.16" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jdmk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]]
  :main ^:skip-aot clj-blueflood.core
  :target-path "target/%s"
  :plugins [[lein-ring "0.8.11"]]
  :ring {:handler clj-blueflood.core/app}
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                                  [alembic "0.2.1"]
                                  [org.clojure/tools.namespace "0.2.4"]
                                  [org.slf4j/slf4j-log4j12 "1.7.7"]
                                  [log4j/log4j "1.2.16"]
                                  ;[http-kit "2.1.16"]
                                  ;[ring/ring-json "0.3.1"]
                                  [ring-mock "0.1.5"]]}}
  ;:java-source-paths ["src/java"]
  ;:javac-options ["-source" "1.6" "-target" "1.6" "-g"]
  )
