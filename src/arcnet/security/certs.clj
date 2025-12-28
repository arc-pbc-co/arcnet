(ns arcnet.security.certs
  "mTLS certificate generation and management for ARCNet.

   Certificate hierarchy:
   - Root CA: ARCNet root certificate authority
   - Intermediate CAs: Per-geozone certificate authorities
   - Node certificates: Per-node client certificates signed by geozone CA

   Security considerations:
   - Private keys are never logged or exposed
   - Certificates use strong algorithms (RSA-4096, SHA-512)
   - Key usage extensions properly constrain certificate usage"
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log])
  (:import [java.io FileOutputStream FileInputStream ByteArrayInputStream]
           [java.math BigInteger]
           [java.security KeyPairGenerator KeyStore SecureRandom Security]
           [java.security.cert X509Certificate CertificateFactory]
           [java.util Date]
           [java.time Instant Duration]
           [org.bouncycastle.asn1.x500 X500Name]
           [org.bouncycastle.asn1.x509 BasicConstraints Extension KeyUsage
            SubjectKeyIdentifier AuthorityKeyIdentifier
            GeneralName GeneralNames]
           [org.bouncycastle.cert X509v3CertificateBuilder]
           [org.bouncycastle.cert.jcajce JcaX509CertificateConverter
            JcaX509v3CertificateBuilder JcaX509ExtensionUtils]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.operator.jcajce JcaContentSignerBuilder]
           [org.bouncycastle.openssl.jcajce JcaPEMWriter]
           [org.bouncycastle.util.io.pem PemObject]))

;; =============================================================================
;; Specs
;; =============================================================================

(s/def ::geozone-id (s/and string? #(re-matches #"[a-z]+-[a-z]+" %)))
(s/def ::node-id (s/and string? #(re-matches #"[a-z0-9\-]+" %)))
(s/def ::validity-days pos-int?)
(s/def ::key-size #{2048 4096})
(s/def ::common-name (s/and string? #(<= 1 (count %) 64)))
(s/def ::organization (s/and string? #(<= 1 (count %) 64)))

(s/def ::ca-config
  (s/keys :req-un [::common-name ::organization ::validity-days]
          :opt-un [::key-size]))

(s/def ::cert-config
  (s/keys :req-un [::common-name ::validity-days]
          :opt-un [::key-size ::geozone-id ::node-id]))

(s/def ::key-pair #(instance? java.security.KeyPair %))
(s/def ::certificate #(instance? X509Certificate %))
(s/def ::cert-chain (s/coll-of ::certificate))

(s/def ::ca-bundle
  (s/keys :req-un [::key-pair ::certificate]))

(s/def ::node-bundle
  (s/keys :req-un [::key-pair ::certificate ::cert-chain]))

;; =============================================================================
;; Security Provider Initialization
;; =============================================================================

(defonce ^:private bc-provider
  (do
    (Security/addProvider (BouncyCastleProvider.))
    (BouncyCastleProvider.)))

(def ^:private secure-random (SecureRandom.))

;; =============================================================================
;; Key Generation
;; =============================================================================

(defn generate-key-pair
  "Generates an RSA key pair with the specified size.
   Default size is 4096 bits for production security."
  ([] (generate-key-pair 4096))
  ([key-size]
   {:pre [(s/valid? ::key-size key-size)]}
   (let [generator (KeyPairGenerator/getInstance "RSA" "BC")]
     (.initialize generator (int key-size) secure-random)
     (.generateKeyPair generator))))

;; =============================================================================
;; Serial Number Generation
;; =============================================================================

(defn- generate-serial
  "Generates a cryptographically random serial number."
  []
  (BigInteger. 128 secure-random))

;; =============================================================================
;; Certificate Validity
;; =============================================================================

(defn- validity-dates
  "Calculates not-before and not-after dates for certificate validity."
  [validity-days]
  (let [now (Instant/now)
        not-before (Date/from (.minus now (Duration/ofHours 1)))  ; 1 hour grace
        not-after (Date/from (.plus now (Duration/ofDays validity-days)))]
    {:not-before not-before
     :not-after not-after}))

;; =============================================================================
;; Root CA Generation
;; =============================================================================

(defn generate-root-ca
  "Generates an ARCNet root CA certificate.

   The root CA is self-signed and has maximum path length for
   signing intermediate CAs.

   Options:
   - :common-name - CA common name (required)
   - :organization - Organization name (required)
   - :validity-days - Certificate validity in days (required)
   - :key-size - RSA key size, default 4096"
  [{:keys [common-name organization validity-days key-size]
    :or {key-size 4096}
    :as config}]
  {:pre [(s/valid? ::ca-config config)]}
  (log/info "Generating ARCNet root CA" {:cn common-name :org organization})
  (let [key-pair (generate-key-pair key-size)
        public-key (.getPublic key-pair)
        private-key (.getPrivate key-pair)
        subject (X500Name. (str "CN=" common-name
                                ",O=" organization
                                ",OU=ARCNet Security"))
        {:keys [not-before not-after]} (validity-dates validity-days)
        serial (generate-serial)
        ext-utils (JcaX509ExtensionUtils.)
        builder (doto (JcaX509v3CertificateBuilder.
                       subject        ; issuer (self-signed)
                       serial
                       not-before
                       not-after
                       subject        ; subject
                       public-key)
                  ;; CA certificate with unlimited path length
                  (.addExtension Extension/basicConstraints true
                                  (BasicConstraints. Integer/MAX_VALUE))
                  ;; Key usage for CA
                  (.addExtension Extension/keyUsage true
                                  (KeyUsage. (bit-or KeyUsage/keyCertSign
                                                     KeyUsage/cRLSign
                                                     KeyUsage/digitalSignature)))
                  ;; Subject Key Identifier
                  (.addExtension Extension/subjectKeyIdentifier false
                                  (.createSubjectKeyIdentifier ext-utils public-key)))
        signer (-> (JcaContentSignerBuilder. "SHA512withRSA")
                   (.setProvider "BC")
                   (.build private-key))
        cert-holder (.build builder signer)
        certificate (-> (JcaX509CertificateConverter.)
                        (.setProvider "BC")
                        (.getCertificate cert-holder))]
    (log/info "Root CA generated successfully"
              {:serial (.toString serial 16)
               :valid-until not-after})
    {:key-pair key-pair
     :certificate certificate}))

;; =============================================================================
;; Geozone Intermediate CA Generation
;; =============================================================================

(defn generate-geozone-ca
  "Generates an intermediate CA for a specific geozone.

   The geozone CA is signed by the root CA and can sign
   node certificates within its geozone.

   Parameters:
   - geozone-id: Geozone identifier (e.g., 'geozone-west')
   - root-ca: Root CA bundle from generate-root-ca
   - config: Certificate configuration map"
  [geozone-id root-ca {:keys [validity-days key-size]
                       :or {key-size 4096}
                       :as config}]
  {:pre [(s/valid? ::geozone-id geozone-id)
         (s/valid? ::ca-bundle root-ca)
         (s/valid? ::validity-days validity-days)]}
  (log/info "Generating geozone intermediate CA" {:geozone geozone-id})
  (let [{:keys [key-pair certificate]} root-ca
        issuer-key (.getPrivate key-pair)
        issuer-cert certificate
        geozone-key-pair (generate-key-pair key-size)
        public-key (.getPublic geozone-key-pair)
        issuer-name (X500Name. (str (.getSubjectX500Principal issuer-cert)))
        subject (X500Name. (str "CN=ARCNet " geozone-id " CA"
                                ",OU=" geozone-id
                                ",O=ARCNet Security"))
        {:keys [not-before not-after]} (validity-dates validity-days)
        serial (generate-serial)
        ext-utils (JcaX509ExtensionUtils.)
        builder (doto (JcaX509v3CertificateBuilder.
                       issuer-name
                       serial
                       not-before
                       not-after
                       subject
                       public-key)
                  ;; Intermediate CA - can only sign end-entity certs
                  (.addExtension Extension/basicConstraints true
                                  (BasicConstraints. 0))
                  ;; Key usage for intermediate CA
                  (.addExtension Extension/keyUsage true
                                  (KeyUsage. (bit-or KeyUsage/keyCertSign
                                                     KeyUsage/cRLSign
                                                     KeyUsage/digitalSignature)))
                  ;; Subject Key Identifier
                  (.addExtension Extension/subjectKeyIdentifier false
                                  (.createSubjectKeyIdentifier ext-utils public-key))
                  ;; Authority Key Identifier (links to root)
                  (.addExtension Extension/authorityKeyIdentifier false
                                  (.createAuthorityKeyIdentifier
                                   ext-utils
                                   (.getPublic key-pair))))
        signer (-> (JcaContentSignerBuilder. "SHA512withRSA")
                   (.setProvider "BC")
                   (.build issuer-key))
        cert-holder (.build builder signer)
        certificate (-> (JcaX509CertificateConverter.)
                        (.setProvider "BC")
                        (.getCertificate cert-holder))]
    (log/info "Geozone CA generated"
              {:geozone geozone-id
               :serial (.toString serial 16)
               :valid-until not-after})
    {:key-pair geozone-key-pair
     :certificate certificate
     :geozone-id geozone-id
     :issuer-cert issuer-cert}))

;; =============================================================================
;; Node Certificate Generation
;; =============================================================================

(defn generate-node-cert
  "Generates a client certificate for a specific node.

   The node certificate is signed by the geozone CA and is used
   for mTLS authentication between nodes.

   Parameters:
   - node-id: Unique node identifier
   - geozone-ca: Geozone CA bundle from generate-geozone-ca
   - config: Certificate configuration map"
  [node-id geozone-ca {:keys [validity-days key-size]
                       :or {key-size 4096}
                       :as config}]
  {:pre [(s/valid? ::node-id node-id)
         (s/valid? ::validity-days validity-days)]}
  (log/info "Generating node certificate" {:node node-id
                                           :geozone (:geozone-id geozone-ca)})
  (let [{:keys [key-pair certificate geozone-id issuer-cert]} geozone-ca
        issuer-key (.getPrivate key-pair)
        node-key-pair (generate-key-pair key-size)
        public-key (.getPublic node-key-pair)
        issuer-name (X500Name. (str (.getSubjectX500Principal certificate)))
        subject (X500Name. (str "CN=" node-id
                                ",OU=" geozone-id
                                ",O=ARCNet Nodes"))
        {:keys [not-before not-after]} (validity-dates validity-days)
        serial (generate-serial)
        ext-utils (JcaX509ExtensionUtils.)
        builder (doto (JcaX509v3CertificateBuilder.
                       issuer-name
                       serial
                       not-before
                       not-after
                       subject
                       public-key)
                  ;; End-entity certificate (not a CA)
                  (.addExtension Extension/basicConstraints true
                                  (BasicConstraints. false))
                  ;; Key usage for client authentication
                  (.addExtension Extension/keyUsage true
                                  (KeyUsage. (bit-or KeyUsage/digitalSignature
                                                     KeyUsage/keyEncipherment)))
                  ;; Subject Key Identifier
                  (.addExtension Extension/subjectKeyIdentifier false
                                  (.createSubjectKeyIdentifier ext-utils public-key))
                  ;; Authority Key Identifier
                  (.addExtension Extension/authorityKeyIdentifier false
                                  (.createAuthorityKeyIdentifier
                                   ext-utils
                                   (.getPublic key-pair))))
        signer (-> (JcaContentSignerBuilder. "SHA512withRSA")
                   (.setProvider "BC")
                   (.build issuer-key))
        cert-holder (.build builder signer)
        node-cert (-> (JcaX509CertificateConverter.)
                      (.setProvider "BC")
                      (.getCertificate cert-holder))]
    (log/info "Node certificate generated"
              {:node node-id
               :geozone geozone-id
               :serial (.toString serial 16)
               :valid-until not-after})
    {:key-pair node-key-pair
     :certificate node-cert
     :cert-chain [node-cert certificate issuer-cert]
     :node-id node-id
     :geozone-id geozone-id}))

;; =============================================================================
;; KeyStore Management
;; =============================================================================

(defn create-keystore
  "Creates a PKCS12 keystore containing a private key and certificate chain.

   Parameters:
   - alias: Key entry alias
   - key-pair: The key pair
   - cert-chain: Certificate chain (node cert -> geozone CA -> root CA)
   - password: Keystore password (char array for security)"
  [alias key-pair cert-chain ^chars password]
  {:pre [(string? alias)
         (s/valid? ::key-pair key-pair)
         (s/valid? ::cert-chain cert-chain)]}
  (let [keystore (KeyStore/getInstance "PKCS12")]
    (.load keystore nil nil)
    (.setKeyEntry keystore
                  alias
                  (.getPrivate key-pair)
                  password
                  (into-array X509Certificate cert-chain))
    keystore))

(defn create-truststore
  "Creates a truststore containing trusted CA certificates.

   Parameters:
   - ca-certs: Map of alias -> certificate for trusted CAs
   - password: Truststore password (char array)"
  [ca-certs ^chars password]
  (let [truststore (KeyStore/getInstance "PKCS12")]
    (.load truststore nil nil)
    (doseq [[alias cert] ca-certs]
      (.setCertificateEntry truststore alias cert))
    truststore))

(defn save-keystore
  "Saves a keystore to a file.

   SECURITY: Ensure the output path has appropriate permissions (600)."
  [keystore path ^chars password]
  (with-open [fos (FileOutputStream. path)]
    (.store keystore fos password))
  (log/info "Keystore saved" {:path path}))

(defn load-keystore
  "Loads a keystore from a file."
  [path ^chars password]
  (let [keystore (KeyStore/getInstance "PKCS12")]
    (with-open [fis (FileInputStream. path)]
      (.load keystore fis password))
    keystore))

;; =============================================================================
;; PEM Export
;; =============================================================================

(defn cert->pem
  "Converts a certificate to PEM format string."
  [^X509Certificate cert]
  (let [sw (java.io.StringWriter.)]
    (with-open [writer (JcaPEMWriter. sw)]
      (.writeObject writer cert))
    (.toString sw)))

(defn private-key->pem
  "Converts a private key to PEM format string.

   SECURITY WARNING: Handle the returned string with extreme care.
   Never log, print, or expose this value."
  [key-pair]
  (let [sw (java.io.StringWriter.)]
    (with-open [writer (JcaPEMWriter. sw)]
      (.writeObject writer (.getPrivate key-pair)))
    (.toString sw)))

;; =============================================================================
;; Certificate Verification
;; =============================================================================

(defn verify-cert-chain
  "Verifies a certificate chain is valid and properly signed.

   Returns true if the chain is valid, throws on verification failure."
  [cert-chain]
  (loop [[cert & rest-chain] cert-chain]
    (if (empty? rest-chain)
      ;; Self-signed root - verify against itself
      (do (.verify cert (.getPublicKey cert))
          true)
      ;; Verify against issuer
      (let [issuer (first rest-chain)]
        (.verify cert (.getPublicKey issuer))
        (recur rest-chain)))))

(defn cert-valid?
  "Checks if a certificate is currently valid (not expired, not yet valid)."
  [^X509Certificate cert]
  (try
    (.checkValidity cert)
    true
    (catch Exception _ false)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn setup-arcnet-pki
  "Sets up the complete ARCNet PKI hierarchy.

   Creates:
   - Root CA
   - Intermediate CAs for each geozone
   - Node certificates for each node in each geozone

   Parameters:
   - geozones: Map of geozone-id -> seq of node-ids
   - root-config: Configuration for root CA
   - geozone-validity-days: Validity for geozone CAs
   - node-validity-days: Validity for node certificates

   Returns a nested map structure:
   {:root-ca {...}
    :geozone-cas {geozone-id {...}}
    :node-certs {geozone-id {node-id {...}}}}"
  [{:keys [geozones root-config geozone-validity-days node-validity-days]}]
  (log/info "Setting up ARCNet PKI" {:geozones (keys geozones)})
  (let [root-ca (generate-root-ca root-config)
        geozone-cas (into {}
                          (for [[geozone-id _nodes] geozones]
                            [geozone-id
                             (generate-geozone-ca
                              geozone-id
                              root-ca
                              {:validity-days geozone-validity-days})]))
        node-certs (into {}
                         (for [[geozone-id nodes] geozones]
                           [geozone-id
                            (into {}
                                  (for [node-id nodes]
                                    [node-id
                                     (generate-node-cert
                                      node-id
                                      (get geozone-cas geozone-id)
                                      {:validity-days node-validity-days})]))]))]
    (log/info "ARCNet PKI setup complete"
              {:root-serial (-> root-ca :certificate .getSerialNumber (.toString 16))
               :geozone-count (count geozone-cas)
               :node-count (reduce + (map count (vals node-certs)))})
    {:root-ca root-ca
     :geozone-cas geozone-cas
     :node-certs node-certs}))
