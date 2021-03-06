(ns car-service.db
(:require [korma.db :as kdb]
          [korma.core :as kc :refer [select insert defentity]]
          [korma.sql.engine :refer [infix]]
          [ragtime.jdbc :as jdbc]
          [ragtime.repl :as repl]
          [environ.core :refer [env]]
          [clj-time.format :as f]
          [clj-time.core :as t]
          [clojure.string :as s]))


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

(def sql-format (f/formatters :year-month-day))

(defn transform-date [date]
  (let [[year month day] (map #(Integer. %) (s/split (str date) #"-"))]
         (f/unparse sql-format (t/to-time-zone (t/date-time year month day)
                                               (t/time-zone-for-offset 2)))))

(declare users cars repairs)

(defentity cars
  (kc/belongs-to users {:fk :user})
  (kc/has-many repairs {:fk :id}))

(defentity repairs
  (kc/belongs-to cars {:fk :car})
  (kc/transform  (fn [row] (update row :date transform-date))))


(defentity cars-with-repairs
  (kc/table
   (kc/subselect cars
                 (kc/modifier "DISTINCT")
                 (kc/fields :id :brand :model :year :user
                            (kc/raw "sum(repairs.price) OVER (PARTITION BY repairs.car)  AS \"totalExpenses\""))
                 (kc/join repairs (= :repairs.car :id)))
   :vehicles)
  (kc/entity-fields [:brand :make] :model :year :totalExpenses)

  (kc/belongs-to users {:fk :user}))

(defentity users
  (kc/pk :email)
  (kc/has-many cars-with-repairs {:fk :user})
  (kc/has-many cars {:fk :user}))

(defn create-user [name email password]
  (insert users
          (kc/values {:name name :email email :password password})))

(defn get-user [email]
  (select users
          (kc/where {:email email})))

(defn gt [l r]
  (infix l ">" r))

(defn lt [l r]
  (infix l "<" r))

(defn get-user-cars [email page per-page order dir from to]
  (let [offset (* per-page (dec page))
        order (if (= order :totalExpenses)
                (kc/raw "sum(repairs.price)")
                order)
        filter (merge {}
                      (if from {:repairs.date [gt (kc/raw (str "'" from "'::date"))]})
                      (if to {:repairs.date [lt (kc/raw (str "'" to "'::date"))]}))]
    (select cars
            (kc/join repairs (= :repairs.car :id))
            (kc/where (merge filter {:user email}))
            (kc/group :id)
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

(defn get-repairs [email sort-by dir]
  (select repairs
          (kc/fields :id :car :date :price :service_description :cars.brand :cars.model)
          (kc/join cars (= :cars.id :car))
          (kc/join users (= :users.email :cars.user))
          (kc/where {:users.email email})
          (kc/order sort-by dir)
          ))

(defn get-car [id]
  (select cars
          (kc/with repairs)
          (kc/where {:id (Integer. id)})))

(defn get-car-repairs [id]
  (select repairs
          (kc/fields :id :car :date :price :service_description)
          (kc/join cars (= :cars.id :car))
          (kc/where {:cars.id (Integer. id)})))


(defn new-repair [repair]
  (insert repairs
          (kc/values (update repair :date #(kc/raw (str "'" % "'::date"))))))

(defn delete-repair [id]
  (kc/delete repairs
             (kc/where {:id id})))

(defn overall []
  (select (kc/transform users (fn [row] (dissoc row :email)))
          (kc/fields :name :email)
          (kc/with cars-with-repairs)
          ))
