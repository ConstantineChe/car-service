(ns car-service.handler-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [car-service.handler :refer :all]
            [car-service.db :as db]
            [korma.db :as kdb]
            [korma.core :as kc]
            [ragtime.repl :as repl]
            [environ.core :refer [env]]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as ch])
  (:import [org.postgresql.util PSQLException]))

(def test-db-connection (kdb/postgres {:db (str (:db env) "_test")
                                       :user (:db-user env)
                                       :password (:db-password env)}))

(def config (db/load-config test-db-connection))

(defn exec-raw [query]
  (sql/with-db-connection [conn db/db-connection]
         (with-open [s (.createStatement (:connection conn))]
           (.executeUpdate s query))))

(defn init-db! [tests]
  (try (exec-raw (str "CREATE DATABASE " (:db env) "_test"))
       (catch PSQLException e
         nil))
  (kdb/defdb test-db test-db-connection)
  (repl/migrate config)
  (try (tests)
       (finally (repl/rollback config (-> config :migrations count)))))

(defn clear-tables! [test]
  (exec-raw "TRUNCATE TABLE users CASCADE")
  (try (test)
       (finally (exec-raw "TRUNCATE TABLE users CASCADE"))))

(use-fixtures :once init-db!)
(use-fixtures :each clear-tables!)

(def tester {:name "Tester" :email "tester@test.de" :password "passwd"})

(defn generate-cars [token]
  (app (-> (mock/request :post "/cars"
                                 (ch/generate-string
                                  {:brand "AUDI" :model "A8" :year 2012 :mileage 123 :token token}))
                     (mock/content-type "application/json")))
  (app (-> (mock/request :post "/cars"
                                 (ch/generate-string
                                  {:brand "KIA" :model "SPORTAGE" :year 2012 :mileage 214 :token token}))
                     (mock/content-type "application/json")))  )

(defn generate-repairs [token]
  (app (-> (mock/request :post "/repairs"
                         (ch/generate-string
                          {:car 1 :price 100 :service_description "test desc" :date "2011-01-07" :token token}))
                     (mock/content-type "application/json")))
  (app (-> (mock/request :post "/repairs"
                         (ch/generate-string
                          {:car 2 :price 100 :service_description "test desc" :date "2011-02-08" :token token}))
           (mock/content-type "application/json")))
  (app (-> (mock/request :post "/repairs"
                         (ch/generate-string
                          {:car 2 :price 150 :service_description "test desc2" :date "2011-03-08" :token token}))
           (mock/content-type "application/json"))))

(defn create-token []
  (app (mock/request :post "/register" tester)))

(defn get-token []
  (->> (:token (ch/parse-string (:body (app (mock/request :get "/access_token" tester))) true))
       rest butlast (apply str)))

(deftest test-auth
  (testing "test authentication"
    (create-token)
    (let [test-token (get-token)]
      (is (= (:message (ch/parse-string (:body (app (mock/request :post "/register" tester))) true)) "User already exists."))
      (is (= (:status (app (mock/request :get "/"))) 403))
      (is (= (:status (app (mock/request :get "/" {:token test-token}))) 200)))))


(deftest test-app
  (create-token)
  (let [token (get-token)]
    (generate-cars token)
    (generate-repairs token)
    (testing "main route"
      (is (= [{:name "Tester"
               :vehicles
               [{:make "AUDI" :model "A8" :year 2012 :totalExpenses 250.00}
                {:make "KIA" :model "SPORTAGE" :year 2012 :totalExpenses 250.00}]
               }]
             (ch/parse-string (:body (app (mock/request :get "/" {:token token}))) true)
             )))
    (testing "cars resource"
      (is (= [{:id 1, :user "tester@test.de", :brand "AUDI", :model "A8", :mileage 123, :year 2012, :photo nil}
              {:id 2, :user "tester@test.de", :brand "KIA", :model "SPORTAGE", :mileage 214, :year 2012, :photo nil}]
             (ch/parse-string (:body (app (mock/request :get "/cars" {:token token}))) true))))
    (testing "repairs resource"
      (is (= [{:id 1 :car 1 :price 100.0 :service_description "test desc" :date "2011-01-07"}
              {:id 2 :car 2 :price 100.0 :service_description "test desc" :date "2011-02-08"}
              {:id 3 :car 2 :price 150.0 :service_description "test desc2" :date "2011-03-08"}]
             (ch/parse-string (:body (app (mock/request :get "/repairs" {:token token}))) true))))
    (testing "not-found route"
      (let [response (app (mock/request :get "/invalid"))]
        (is (= (:status response) 404))))))
