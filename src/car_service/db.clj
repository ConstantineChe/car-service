(ns car-service.db
(:require [korma.db :as kdb]
          [korma.core :as kc :refer [select insert defentity]]
          [ragtime.jdbc :as jdbc]
          [ragtime.repl :as repl]
          [environ.core :refer [env]]
          [clj-time.format :as f]))


;; postgresql database connection
(def db-connection (kdb/postgres {:db (:db env)
                                  :user (:db-user env)
                                  :password (:db-password env)}))


(defn load-config
  "Configure migrations connection and folder."
  [connection]
  {:datastore  (jdbc/sql-database connection)
   :migrations (jdbc/load-resources "migrations")})

(defn migrate
  "This function performs all unfinished migrations.
   It will be invoked by 'lein migrate'"
  [& args]
  (repl/migrate (load-config)))

(defn rollback
  "This funtion preforms rollback by one migration from current state.
  It will be invoced by 'lein rollback'"
  [& args]
  (repl/rollback (load-config)))

;; Korma database connection
(kdb/defdb db db-connection)

(declare users cars repairs)

(defentity cars
  (kc/belongs-to users {:fk :user})
  (kc/has-many repairs {:fk :id}))

(defentity repairs
  (kc/entity-fields :price)
  (kc/belongs-to cars {:fk :car}))


(defentity cars-with-repairs
  (kc/table
   (kc/subselect cars
                 (kc/fields :brand :model :year :user
                            (kc/raw "sum(repairs.price) OVER (PARTITION BY repairs.car)  AS \"totalExpenses\""))
                 (kc/join repairs (= :repairs.car :id)))
   :vehicles)
  (kc/entity-fields [:brand :make] :model :year :totalExpenses)

  (kc/belongs-to users {:fk :user}))

(defentity repairs-full
  (kc/table :repairs))

(defentity users
  (kc/pk :email)
  (kc/entity-fields :name :email)
  (kc/has-many cars-with-repairs {:fk :user})
  (kc/has-many cars {:fk :user})
  (kc/transform (fn [row] (dissoc row :email))))

(defentity users-full
  (kc/table :users))

(defn create-user [name email password]
  (insert users
          (kc/values {:name name :email email :password password})))

(defn get-user [email]
  (select users-full
          (kc/where {:email email})))

(defn get-user-cars [email page per-page order dir]
  (let [offset (* per-page (dec page))
        order (if (= order :totalExpenses)
                (kc/raw "(sum(repairs.price) OVER (PARTITION BY repairs.car)")
                order)]
    (select cars
            (kc/join repairs (= :repairs.car :id))
            (kc/where {:user email})
            (kc/order order dir)
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

(defn get-repairs [email]
  (select repairs-full
          (kc/join cars (= :cars.id :car))
          (kc/join users-full (= :users.email :cars.user))
          (kc/where {:users.email email})))


(defn new-repair [repair]
  (insert repairs
          (kc/values (update-in repair [:date] #(try (f/parse (f/formatters :year-month-day)) (catch java.lang.Exception e (prn %)))))))

(defn delete-repair [id]
  (kc/delete repairs
             (kc/where {:id id})))

(defn overall []
  (select users
          (kc/with cars-with-repairs)
          ))
