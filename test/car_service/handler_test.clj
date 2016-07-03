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

(defn get-token [res]
  (->> (ch/parse-string (:body res) true) :token rest butlast (apply str)))

(deftest test-auth
  (testing "test authentication"
    (let [reg-res (app (mock/request :post "/register" tester))
          token (get-token reg-res)]
      (is (= (:message (ch/parse-string (:body (app (mock/request :post "/register" tester))) true)) "User already exists."))
      (is (= (:status (app (mock/request :get "/"))) 403))
      (is (= (:status (app (mock/request :get "/" {:token token}))) 200)))))

(deftest test-app
  (testing "main route"
    (let [response (app (mock/request :get "/"))]
      (is (= (:status response) 403))))

  (testing "not-found route"
    (let [response (app (mock/request :get "/invalid"))]
      (is (= (:status response) 404)))))
