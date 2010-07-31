(ns weave.core
  (:refer-clojure)
  (:import
     [java.security
      Key
      KeyFactory]
     [java.security.interfaces RSAPrivateKey]
     [java.security.spec
      KeySpec
      PKCS8EncodedKeySpec]
     [javax.crypto
      Cipher
      SecretKey
      SecretKeyFactory]
     [javax.crypto.spec
      PBEKeySpec
      IvParameterSpec
      SecretKeySpec])
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

(defn base64-decode [^String encoded]
  (org.apache.commons.codec.binary.Base64/decodeBase64 encoded))

(defn auth-filter [username password]
  (http/preemptive-basic-auth-filter (str username ":" password)))

;; In case you want to compute your own.
(defn weave-base-url [server path version]
  (str "https://" server "/" path version "/"))

(defn weave-get 
  ([url]
   (weave-get url nil nil))
  ([url auth-filter query]
   (http/get url
             :query query
             :as :json
             :filters (when auth-filter [auth-filter])
             :headers-as :map)))

(defn weave-get-authenticated [url username password query]
  (:content
     (weave-get url
                (auth-filter username password)
                query)))
  
(defn weave-get-content
  ([base-url username password remainder]
   (weave-get-content base-url username password remainder nil))
  ([base-url username password remainder query]
   (weave-get-authenticated (str (or base-url *mozilla-sync-url*) username remainder)
                            username password query)))

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

;; Returns the contents of a collection.
(defn weave-collection
  [base-url username password coll parameters]
  (weave-get-content base-url username password (str "/storage/" (name coll))
                     ;; Query map.
                     parameters))

;; Returns an object by id, or a collection thereof by pulling the collection
;; first.
(defn weave-object
  ([base-url username password coll id]
   (if id
     (let [con (weave-get-content base-url username password (str "/storage/" (name coll) "/" id))
           payload (json/read-json (:payload con) :keywordize)]
       (assoc con :payload payload))
     
     ;; Omit the id and we'll produce a map from ID to object.
     (let [ids (weave-collection base-url username password coll nil)]
       (zipmap ids
               (map (partial weave-object base-url username password coll)
                    ids)))))
  
  ;; Shortcut for no-id.
  ([base-url username password coll]
   (weave-object base-url username password coll nil)))
  

;;;
;;; Decryption.
;;; 

(defn ^RSAPrivateKey private-key-bytes->rsa-private-key [b]
  (let [fac (KeyFactory/getInstance "RSA")]
    (.generatePrivate fac (PKCS8EncodedKeySpec. b))))

(defn ^SecretKeySpec passphrase->aes-key [^String passphrase ^bytes salt]
  (let [^SecretKeyFactory f  (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA1")
        ^KeySpec          ks (PBEKeySpec. (.toCharArray passphrase) salt 4096 256)
        ^SecretKey        s  (.generateSecret f ks)]
    (SecretKeySpec. (.getEncoded s) "AES")))

;; Makes a fetch.
(defn fetch-private-key
  [base-url username password ^String passphrase]
  (let [obj                    (weave-object base-url username password "keys" "privkey")
        payload                (:payload obj)
        salt                   (:salt payload)
        iv                     (IvParameterSpec. (base64-decode (:iv payload)))
        key-data               (:keyData payload)
        public-key-uri         (:publicKeyUri payload)
        ^SecretKeySpec passkey (passphrase->aes-key passphrase (base64-decode salt))
        ^Cipher cipher         (Cipher/getInstance "AES/CBC/PKCS5Padding")]
    (.init cipher Cipher/DECRYPT_MODE passkey iv)
    (.doFinal cipher (base64-decode key-data))))

(defn fetch-bulk-key
  [username password private-key url]
  (let [data (weave-get-authenticated url username password nil)
        payload (json/read-json (:payload data) :keywordize)
        {:keys [wrapped hmac]} (first (vals (:keyring payload)))]
    
    (let [^KeySpec       ks     (private-key-bytes->rsa-private-key private-key)
          ^Cipher        cipher (Cipher/getInstance "RSA/ECB/PKCS1Padding")]
        (.init cipher Cipher/DECRYPT_MODE ks)
        (.doFinal cipher (base64-decode wrapped)))))

(defn ^String decrypt-with-key [base64-ciphertext base64-IV bulk-key]
  ;; AES256 CBC.
  (let [^Key    s-key  (SecretKeySpec. bulk-key "AES")
        ^Cipher cipher (Cipher/getInstance "AES/CBC/PKCS5Padding")]
    (.init cipher Cipher/DECRYPT_MODE s-key (IvParameterSpec. (base64-decode base64-IV)))
    (String. (.doFinal cipher (base64-decode base64-ciphertext)) "UTF-8")))
  
(defn decrypt-payload
  "Provide a parsed JSON payload, and a dictionary containing possible decryption input.
   Returns a pair: the JSON response, and a dictionary that is suitable for reuse as
   input."
  [payload {:keys [passphrase private-key bulk-keys] :as opts}]
  (let [{:keys [ciphertext hmac IV encryption]} payload]
    
    (let [out (atom (or opts {}))
          store-bulk (fn [b] (swap! out assoc-in [:bulk-keys encryption] b) b)
          store-priv (fn [p]   (swap! out assoc :private-key p) p)
          
          ;; Figure out the bulk key. It might have been explicitly provided,
          ;; be obtainable by a known private key, or we need to fetch them 
          ;; both using the passphrase.
          bulk-key    (or (and bulk-keys
                               (get bulk-keys encryption))
                          (and private-key
                               (store-bulk
                                 (fetch-bulk-key *username* *password* private-key encryption)))
                          (and passphrase
                               (store-bulk
                                 (fetch-bulk-key
                                   *username* *password* 
                                   (store-priv
                                     (fetch-private-key *base-url* *username* *password* passphrase))
                                   encryption))))]
      (if bulk-key
        [(json/read-json (decrypt-with-key ciphertext IV bulk-key) :keywordize)
         @out]
        (throw (Exception. (str "Unable to fetch bulk key for <" encryption ">.")))))))
  
;;;
;;; Shortened versions.
;;; 

;; Macro so we can shorten calls.
(defn- weave-function-definition [[long-name short-name & args]]
  `(defn ~short-name [~@args]
     (let [u# `~*username*
           p# `~*password*]
       (when-not (and u# p#)
         (throw (Exception. "Either *username* or *password* are nil.")))
       (~long-name `~*base-url* u# p# ~@args))))

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
