(ns car-service.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.util.response :refer [redirect response]]
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
      (try (:user (jwt/unsign token secret))
           (catch clojure.lang.ExceptionInfo e
             false))))

(defn access-token [request]
  (let [{:keys [email password]} (:params request)
        user (first (db/get-user email))]
    (prn email password user)
    (if (check password (:password user))
      (response {:token (ch/encode (jwt/sign {:user (:email user)} secret))})
      (response {:status "error"
                 :message "invalid password"}))))

(defn register [request]
  (let [{:keys [name email password]} (:params request)]
    (db/create-user name email (encrypt password))
    (response {:status "success"})))

(defn get-cars [request]
  (let [email (unsign-token request)
        page (if-let [page (-> request :params :page)] (Integer. page) 1)
        per-page (if-let [per-page (-> request :params :per-page)] (Integer. per-page) 10)]
    (if email
      (response (db/get-user-cars email page per-page))
      (response {:status "error" :message "invalid token"}))))

(defn new-car [request]
  (let [email (unsign-token request)
        car (select-keys (:params request) [:brand :model :mileage :year :photo])]
    (if email
      (db/new-car (assoc car :user email))
      (response {:status "error" :message "invalid token"}))))

(defn update-car [request]
  (let [params (:params request)
        email (unsign-token request)]
    (if email
      (do (db/update-car (:id params) email
                         (select-keys params [:brand :model :mileage :year :photo]))
          (response {:status "updated" :id (:id params)}))
      (response {:status "error" :message "not authenticated"}))))

(defn delete-car [request]
  (let [id (:id (:params request))
        email (unsign-token request)]
    (if email
      (db/delete-car id email)
      (response {:status "error" :message "not authenticated"}))))

(defn owerall [request]
  (let [auth? (unsign-token request)]
    (if auth?
      (response (db/owerall))
      (response {:status "error" :message "not authenticated"}))))

(defroutes app-routes
  (GET "/access_token" [] access-token)
  (POST "/register" [] register)
  (GET "/cars" [] get-cars)
  (POST "/cars" [] new-car)
  (PUT "/cars" [] update-car)
  (DELETE "/cars" [] delete-car)
  (GET "/" [] owerall)
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