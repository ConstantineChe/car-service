(ns car-service.db
(:require [korma.db :as kdb]
          [korma.core :as kc :refer [select insert defentity]]
          [ragtime.jdbc :as jdbc]
          [ragtime.repl :as repl]
          [environ.core :refer [env]]))


;; postgresql database connection
(def db-connection (kdb/postgres {:db (:db env)
                                  :user (:db-user env)
                                  :password (:db-password env)}))


(defn load-config
  "Configure migrations connection and folder."
  []
  {:datastore  (jdbc/sql-database db-connection)
   :migrations (jdbc/load-resources "migrations")})

(defn migrate
  "This function performs all unfinished migrations.
   It will be invoked by 'lein migrate'"
  [& args]
  (prn (load-config))
  (repl/migrate (load-config)))

(defn rollback
  "This funtion preforms rollback by one migration from current state.
  It will be invoced by 'lein rollback'"
  [& args]
  (repl/rollback (load-config)))

;; Korma database connection
(kdb/defdb db db-connection)

(defentity users)

(defentity cars)

(defentity repairs)

(defn create-user [name email password]
  (insert users
          (kc/values {:name name :email email :password password})))

(defn get-user [email]
  (select users
          (kc/where {:email email})))

(defn get-user-cars [email page per-page]
  (let [offset (* per-page (dec page))]
    (select cars
            (kc/where {:user email})
            (kc/offset offset)
            (kc/limit per-page))))

(defn new-car [car]
  (insert cars
          (kc/values car)))

(defn delete-car [id email]
  (kc/delete cars (kc/where {:id id :user email})))

(defn update-car [id email data]
  (kc/update cars
             (kc/set-fields data)
             (kc/where {:id id :user email})))

(defn owerall []
  (select users
          (kc/fields :name :cars.brand :cars.model :cars.year
                     (kc/raw "sum(repairs.price) OVER (PARTITION BY repairs.car)  AS totalExpenses"))
          (kc/join cars (= :cars.user :email))
          (kc/join repairs (= :repairs.car :cars.id))))
