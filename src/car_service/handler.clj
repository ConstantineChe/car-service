(ns car-service.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.util.response :refer [redirect response status]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response wrap-json-params]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [buddy.auth.backends :as backends]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [cheshire.core :as ch]
            [buddy.hashers :refer [encrypt check]]
            [buddy.sign.jwt :as jwt]
            [car-service.db :as db]))


(def secret "secret")

(defn unsign-token [request]
  (let [{:keys [token] :or {token ""}} (:params request)]
    (if token
      (try (:user (jwt/unsign token secret))
           (catch clojure.lang.ExceptionInfo e
             false))
      false)))

(defn access-token [request]
  (let [{:keys [email password]} (:params request)
        user (first (db/get-user email))]
    (if (check password (:password user))
      (response {:token (ch/encode (jwt/sign {:user (:email user)} secret))})
      (response {:status "error"
                 :message "invalid password"}))))

(defn register [request]
  (let [{:keys [name email password]} (:params request)]
    (try (do (db/create-user name email (encrypt password))
             (response {:status "success"
                        :token (ch/encode (jwt/sign {:user email} secret))}))
         (catch org.postgresql.util.PSQLException e
           (if (and (= (.getErrorCode e) 0)
                    (= (.getSQLState e) "23505"))
             (response {:message "User already exists."})
             (response {:message (.getMessage e) :code (.getSQLState e)}))))))

(defn get-cars [request]
  (let [email (unsign-token request)
        {:keys [page per-page sort-by dir repaired-from repaired-to]} (:params request)
        page (if page (Integer. page) 1)
        per-page (if per-page (Integer. per-page) 10)
        sort-by (if-not sort-by
                  (if-let [last-sort (-> request :session :sort-by)] last-sort :id)
                  (if (#{"mileage" "totalExpenses"} sort-by) (keyword sort-by) :id))
        dir (if-not dir
              (if-let [last-dir (-> request :session :dir)] last-dir :ASC)
              (if (#{"ASC" "DESC"} dir) (keyword dir) :ASC))]
    (if email
      (-> (response (db/get-user-cars email page per-page sort-by dir
                                      repaired-from repaired-to))
          (update :session assoc :sort-by sort-by :dir dir))
      (-> (response {:status "error" :message "invalid token"})
          (status 403)))))

(defn new-car [request]
  (let [email (unsign-token request)
        car (select-keys (:params request) [:brand :model :mileage :year :photo])]
    (if email
      (db/new-car (assoc car :user email))
      (-> (response {:status "error" :message "invalid token"})
          (status 403)))))

(defn update-car [request]
  (let [params (:params request)
        email (unsign-token request)]
    (if email
      (do (db/update-car (:id params) email
                         (select-keys params [:brand :model :mileage :year :photo]))
          (response {:status "updated" :id (:id params)}))
      (-> (response {:status "error" :message "not authenticated"})
          (status 403)))))

(defn delete-car [request]
  (let [id (:id (:params request))
        email (unsign-token request)]
    (if email
      (db/delete-car id email)
      (-> (response {:status "error" :message "not authenticated"})
          (status 403)))))

(defn get-repairs [request]
  (let [email (unsign-token request)
        {:keys [sort-by dir]} (:params request)
        sort-by (if (#{"date" "price"} sort-by) (keyword sort-by) :id)
        dir (if (#{"ASC" "DESC"} dir) (keyword dir) :ASC)]
    (if email
      (db/get-repairs email sort-by dir)
      (-> (response {:status "error" :message "not authenticated"})
          (status 403)))    ))

(defn new-repair [request]
  (let [email (unsign-token request)
        repair (select-keys (:params request) [:car :date :price :service_description])]
    (if email
      (db/new-repair repair)
      (-> (response {:status "error" :message "invalid token"})
          (status 403)))))

(defn delete-repair [request]
  (let [id (:id (:params request))
        email (unsign-token request)]
    (if email
      (db/delete-repair id)
      (-> (response {:status "error" :message "not authenticated"})
          (status 403)))))

(defn get-car [id request]
    (let [email (unsign-token request)]
    (if email
      (db/get-car id)
      (-> (response {:status "error" :message "not authenticated"})
          (status 403)))))

(defn get-car-repairs [id request]
  (let [email (unsign-token request)]
    (if email
      (db/get-car-repairs id)
      (-> (response {:status "error" :message "not authenticated"})
          (status 403)))))

(defn overall [request]
  (let [auth? (unsign-token request)]
    (if auth?
      (response (db/overall))
      (-> (response {:status "error" :message "not authenticated"})
          (status 403)))))

(defroutes app-routes
  (GET "/access_token" [] access-token)
  (POST "/register" [] register)
  (GET "/cars" [] get-cars)
  (GET "/cars/:id" [id :as request] (get-car id request))
  (POST "/cars" [] new-car)
  (PUT "/cars" [] update-car)
  (DELETE "/cars" [] delete-car)
  (GET "/repairs" [] get-repairs)
  (POST "/repairs" [] new-repair)
  (DELETE "/repairs" [] delete-repair)
  (GET "/repairs/:id" [id :as request] (get-car-repairs id request))
  (GET "/" [] overall)
  (route/not-found "Not Found"))


(def backend (backends/jws {:secret secret}))

(defn wrap-middleware [routes]
  (-> routes
      (wrap-json-body {:keywords? true :bigdecimals? true})
      wrap-json-response
      wrap-keyword-params
      wrap-json-params
      (wrap-authentication backend)
      (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))
      wrap-reload))

(def app
  (wrap-middleware app-routes))
