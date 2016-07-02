(defproject car-service "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [compojure "1.4.0"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-devel "1.5.0"]
                 [ring/ring-json "0.4.0"]
                 [ring-middleware-format "0.7.0"]
                 [environ "1.0.3"]
                 [buddy "1.0.0"]
                 [korma "0.4.2"]
                 [ragtime "0.5.2"]
                 [cheshire "5.6.3"]
                 [org.clojure/java.jdbc "0.3.7"]
                 [postgresql/postgresql "9.1-901-1.jdbc4"]]
  :plugins [[lein-ring "0.9.7"]
            [lein-environ "1.0.3"]]
  :ring {:handler car-service.handler/app}
  :aliases {"migrate"  ["run" "-m" "car-service.db/migrate"]
            "rollback" ["run" "-m" "car-service.db/rollback"]}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.0"]]}})
