(ns weave.core
  (:refer-clojure)
  (:require
     [clojure.contrib.json :as json]
     [com.twinql.clojure.http :as http]))

;; Default service URL.
(def *mozilla-sync-url* "https://phx-sync223.services.mozilla.com/1.0/")

;; TODO: pre-build auth-filter?
(def *base-url* nil)
(def *username* nil)
(def *password* nil)

;;;
;;; Utilities.
;;; 

(defn- auth-filter [username password]
  (http/preemptive-basic-auth-filter (str username ":" password)))

;; In case you want to compute your own.
(defn weave-base-url [server path version]
  (str "https://" server "/" path version "/"))

(defn weave-get [url auth-filter query]
  (println "Fetching " url)
  (http/get url
            :query query
            :as :json
            :filters [auth-filter]
            :headers-as :map))

(defn weave-get-content
  ([base-url username password remainder]
   (weave-get-content base-url username password remainder nil))
  ([base-url username password remainder query]
   (:content
      (weave-get (str (or base-url *mozilla-sync-url*) username remainder)
                 (auth-filter username password)
                 query))))

;;;
;;; Operations.
;;; 

;; Returns map of collection => timestamp.
(defn weave-collections-timestamps [base-url username password]
  (weave-get-content base-url username password "/info/collections"))

;; Returns map of collection => count.
(defn weave-collections-counts [base-url username password]
  (weave-get-content base-url username password "/info/collection_counts"))

;; Returns a sequence of collections.
(defn weave-collections [base-url username password]
  (keys (weave-collections-counts base-url username password)))

;; Returns a pair.
(defn weave-usage [base-url username password]
  (weave-get-content base-url username password "/info/quota"))

(defn weave-collection
  [base-url username password coll parameters]
  (weave-get-content base-url username password (str "/storage/" (name coll))
                     ;; Query map.
                     parameters))

(defn weave-object
  ([base-url username password coll id]
   (if id
     (weave-get-content base-url username password (str "/storage/" (name coll) "/" id))
     
     ;; Omit the id and we'll produce a map from ID to object.
     (let [ids (weave-collection base-url username password coll nil)]
       (zipmap ids
               (map (partial weave-object base-url username password coll)
                    ids)))))
  
  ;; Shortcut for no-id.
  ([base-url username password coll]
   (weave-object base-url username password coll nil)))
  
  
;;;
;;; Shortened versions.
;;; 

;; Macro so we can shorten calls.
(defn- weave-function-definition [[long-name short-name & args]]
  `(defn ~short-name [~@args]
     (~long-name `~*base-url* `~*username* `~*password* ~@args)))

(defmacro weave-functions [& fs]
  `(do
     ~@(map weave-function-definition fs)))

(weave-functions
  [weave-usage usage]
  [weave-collections collections]
  [weave-collections-counts collections-counts]
  [weave-collections-timestamps collections-timestamps]
  [weave-collection collection coll parameters]
  [weave-object object coll id])

(defmacro with-weave [[username password & more] & body]
  (let [[base-url] more]
    `(binding [`~*base-url* ~base-url
               `~*username* ~username
               `~*password* ~password]
       ~@body)))


;;;
;;; Usage:
;;; (require ['weave.core :as 'weave])
;;; 
;;; (weave/with-weave ["john" "pass"]
;;;   (let [coll (weave/collections)]
;;;     (when (contains? coll :tabs)
;;;       (weave/collection :tabs {}))))
;;;       
