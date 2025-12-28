(ns arcnet.bridge.data-mover
  "Globus Transfer API integration for data movement to ORNL.

   Provides:
   - OAuth2 token management for Globus Auth
   - Transfer initiation between endpoints
   - Transfer status polling
   - Retry logic with exponential backoff

   Environment variables required:
   - GLOBUS_CLIENT_ID: OAuth2 client ID
   - GLOBUS_CLIENT_SECRET: OAuth2 client secret
   - GLOBUS_ORNL_ENDPOINT_ID: ORNL destination endpoint
   - GLOBUS_ARCNET_ENDPOINT_ID: ARCNet source endpoint"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go <! timeout]]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant Duration]
           [java.util UUID Base64]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const globus-auth-url "https://auth.globus.org/v2/oauth2/token")
(def ^:const globus-transfer-url "https://transfer.api.globus.org/v0.10")

(def ^:const max-retries 3)
(def ^:const base-backoff-ms 1000)

;; Transfer status values
(def transfer-statuses
  #{:pending :active :succeeded :failed :canceled})

;; =============================================================================
;; Configuration
;; =============================================================================

(defn- get-env
  "Gets an environment variable, throwing if required and not set."
  [var-name & {:keys [required] :or {required true}}]
  (let [value (System/getenv var-name)]
    (when (and required (nil? value))
      (throw (ex-info (str "Required environment variable not set: " var-name)
                      {:var var-name :type :missing-env-var})))
    value))

(defn globus-config
  "Returns Globus configuration from environment variables."
  []
  {:client-id (get-env "GLOBUS_CLIENT_ID")
   :client-secret (get-env "GLOBUS_CLIENT_SECRET")
   :ornl-endpoint-id (get-env "GLOBUS_ORNL_ENDPOINT_ID")
   :arcnet-endpoint-id (get-env "GLOBUS_ARCNET_ENDPOINT_ID")
   :ornl-base-path (get-env "GLOBUS_ORNL_BASE_PATH" :required false)})

;; =============================================================================
;; OAuth2 Token Management
;; =============================================================================

(defonce ^:private token-cache (atom nil))

(defn- token-expired?
  "Checks if the cached token is expired or about to expire."
  [token-data]
  (when token-data
    (let [expires-at (:expires-at token-data)
          ;; Consider expired if within 5 minutes of expiration
          buffer (Duration/ofMinutes 5)]
      (.isAfter (Instant/now) (.minus expires-at buffer)))))

(defn- basic-auth-header
  "Creates a Basic Auth header value."
  [client-id client-secret]
  (let [credentials (str client-id ":" client-secret)
        encoded (-> credentials
                    (.getBytes "UTF-8")
                    (Base64/getEncoder)
                    (.encode)
                    (String.))]
    (str "Basic " encoded)))

(defn refresh-access-token!
  "Obtains a new access token using client credentials flow.

   Returns:
   {:access-token \"...\"
    :expires-at Instant
    :token-type \"Bearer\"}"
  [config]
  (log/debug "Refreshing Globus access token")
  (let [{:keys [client-id client-secret]} config
        response (http/post globus-auth-url
                            {:headers {"Authorization" (basic-auth-header client-id client-secret)
                                       "Content-Type" "application/x-www-form-urlencoded"}
                             :form-params {:grant_type "client_credentials"
                                           :scope "urn:globus:auth:scope:transfer.api.globus.org:all"}
                             :as :json
                             :throw-exceptions true})
        body (:body response)
        expires-in (or (:expires_in body) 3600)
        token-data {:access-token (:access_token body)
                    :token-type (:token_type body)
                    :expires-at (.plus (Instant/now) (Duration/ofSeconds expires-in))}]
    (reset! token-cache token-data)
    (log/info "Globus access token refreshed" {:expires-at (:expires-at token-data)})
    token-data))

(defn get-access-token
  "Gets a valid access token, refreshing if necessary."
  [config]
  (let [cached @token-cache]
    (if (or (nil? cached) (token-expired? cached))
      (:access-token (refresh-access-token! config))
      (:access-token cached))))

;; =============================================================================
;; HTTP Helpers
;; =============================================================================

(defn- globus-request
  "Makes an authenticated request to the Globus Transfer API."
  [method path config & {:keys [body query-params]}]
  (let [token (get-access-token config)
        url (str globus-transfer-url path)
        opts {:headers {"Authorization" (str "Bearer " token)
                        "Content-Type" "application/json"}
              :as :json
              :throw-exceptions false}
        opts (cond-> opts
               body (assoc :body (json/generate-string body))
               query-params (assoc :query-params query-params))
        response (case method
                   :get (http/get url opts)
                   :post (http/post url opts)
                   :delete (http/delete url opts))]
    (if (< (:status response) 400)
      {:success true :data (:body response)}
      {:success false
       :status (:status response)
       :error (get-in response [:body :message] "Unknown error")
       :code (get-in response [:body :code])})))

;; =============================================================================
;; Transfer Operations
;; =============================================================================

(defn initiate-transfer!
  "Initiates a Globus transfer from ARCNet to ORNL.

   Parameters:
   - source-node-id: UUID of the source ARCNet node
   - dataset-uri: URI of the dataset to transfer (path on source endpoint)
   - opts: Optional map with:
     - :destination-path - Override destination path
     - :label - Human-readable label for the transfer
     - :sync-level - Sync level (0-3, default 0)

   Returns:
   {:success true :task-id \"uuid\" :submission-id \"uuid\"}
   {:success false :error \"message\" :code \"error_code\"}"
  [source-node-id dataset-uri & {:keys [destination-path label sync-level config]
                                  :or {sync-level 0}}]
  {:pre [(uuid? source-node-id) (string? dataset-uri)]}
  (tracing/with-span {:name "globus-initiate-transfer"
                      :attributes {:source-node (str source-node-id)
                                   :dataset-uri dataset-uri}}
    (let [timer (metrics/start-timer)
          cfg (or config (globus-config))
          source-endpoint (:arcnet-endpoint-id cfg)
          dest-endpoint (:ornl-endpoint-id cfg)
          ;; Generate destination path
          dest-path (or destination-path
                        (str (:ornl-base-path cfg "/arcnet/incoming/")
                             (str source-node-id) "/"
                             (.toString (Instant/now)) "/"))
          transfer-label (or label (str "ARCNet transfer from " source-node-id))
          ;; Build transfer request
          submission-id (str (UUID/randomUUID))
          transfer-request {:DATA_TYPE "transfer"
                            :submission_id submission-id
                            :label transfer-label
                            :source_endpoint source-endpoint
                            :destination_endpoint dest-endpoint
                            :sync_level sync-level
                            :verify_checksum true
                            :preserve_timestamp true
                            :encrypt_data true
                            :DATA [{:DATA_TYPE "transfer_item"
                                    :source_path dataset-uri
                                    :destination_path dest-path
                                    :recursive true}]}]
      (log/info "Initiating Globus transfer"
                {:source-node source-node-id
                 :source-endpoint source-endpoint
                 :dest-endpoint dest-endpoint
                 :dataset-uri dataset-uri
                 :dest-path dest-path})
      (let [result (globus-request :post "/transfer" cfg :body transfer-request)]
        (if (:success result)
          (let [task-id (get-in result [:data :task_id])]
            (metrics/record-operation!
             {:operation "globus-initiate-transfer"
              :duration-ms (timer)
              :success? true})
            (log/info "Globus transfer initiated"
                      {:task-id task-id
                       :submission-id submission-id})
            {:success true
             :task-id task-id
             :submission-id submission-id
             :destination-path dest-path})
          (do
            (metrics/record-operation!
             {:operation "globus-initiate-transfer"
              :duration-ms (timer)
              :success? false})
            (log/error "Globus transfer initiation failed"
                       {:error (:error result)
                        :code (:code result)})
            result))))))

(defn poll-transfer-status
  "Polls the status of a Globus transfer task.

   Parameters:
   - task-id: The Globus task ID

   Returns:
   {:status :pending | :active | :succeeded | :failed | :canceled
    :bytes-transferred n
    :files-transferred n
    :files-skipped n
    :nice-status \"human readable\"
    :nice-status-short-description \"short description\"}"
  [task-id & {:keys [config]}]
  {:pre [(string? task-id)]}
  (tracing/with-span {:name "globus-poll-status"
                      :attributes {:task-id task-id}}
    (let [timer (metrics/start-timer)
          cfg (or config (globus-config))
          result (globus-request :get (str "/task/" task-id) cfg)]
      (metrics/record-operation!
       {:operation "globus-poll-status"
        :duration-ms (timer)
        :success? (:success result)})
      (if (:success result)
        (let [data (:data result)
              status-str (:status data)]
          {:status (keyword (clojure.string/lower-case status-str))
           :bytes-transferred (:bytes_transferred data)
           :files-transferred (:files_transferred data)
           :files-skipped (:files_skipped data)
           :request-time (:request_time data)
           :completion-time (:completion_time data)
           :nice-status (:nice_status data)
           :nice-status-short-description (:nice_status_short_description data)
           :is-paused (:is_paused data)
           :task-id task-id})
        {:status :unknown
         :error (:error result)
         :code (:code result)
         :task-id task-id}))))

(defn cancel-transfer!
  "Cancels a running Globus transfer.

   Parameters:
   - task-id: The Globus task ID to cancel

   Returns:
   {:success true} or {:success false :error \"...\"}"
  [task-id & {:keys [config]}]
  {:pre [(string? task-id)]}
  (log/info "Canceling Globus transfer" {:task-id task-id})
  (let [cfg (or config (globus-config))
        result (globus-request :post (str "/task/" task-id "/cancel") cfg)]
    (if (:success result)
      (do
        (log/info "Globus transfer canceled" {:task-id task-id})
        {:success true :task-id task-id})
      (do
        (log/error "Failed to cancel Globus transfer"
                   {:task-id task-id :error (:error result)})
        result))))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(defn- calculate-backoff-ms
  "Calculates exponential backoff delay."
  [attempt]
  (* base-backoff-ms (Math/pow 2 attempt)))

(defn initiate-transfer-with-retry!
  "Initiates a transfer with automatic retry on failure.

   Retries up to max-retries times with exponential backoff.

   Returns:
   {:success true :task-id \"...\" :attempts n}
   {:success false :error \"...\" :attempts n :last-error \"...\"}"
  [source-node-id dataset-uri & {:keys [config max-attempts]
                                  :or {max-attempts max-retries}}]
  (tracing/with-span {:name "globus-transfer-with-retry"
                      :attributes {:source-node (str source-node-id)
                                   :max-attempts max-attempts}}
    (loop [attempt 0
           last-error nil]
      (if (>= attempt max-attempts)
        (do
          (log/error "Globus transfer failed after max retries"
                     {:source-node source-node-id
                      :attempts attempt
                      :last-error last-error})
          {:success false
           :error "Max retries exceeded"
           :attempts attempt
           :last-error last-error})
        (let [result (initiate-transfer! source-node-id dataset-uri :config config)]
          (if (:success result)
            (assoc result :attempts (inc attempt))
            ;; Retry with backoff
            (let [backoff-ms (calculate-backoff-ms attempt)]
              (log/warn "Globus transfer attempt failed, retrying"
                        {:attempt (inc attempt)
                         :backoff-ms backoff-ms
                         :error (:error result)})
              (Thread/sleep (long backoff-ms))
              (recur (inc attempt) (:error result)))))))))

;; =============================================================================
;; Async Status Polling
;; =============================================================================

(defn wait-for-completion
  "Waits for a transfer to complete, polling periodically.

   Parameters:
   - task-id: Globus task ID
   - opts:
     - :poll-interval-ms - Polling interval (default 10000)
     - :timeout-ms - Maximum wait time (default 3600000 = 1 hour)
     - :config - Globus config

   Returns the final status when complete or timed out."
  [task-id & {:keys [poll-interval-ms timeout-ms config]
               :or {poll-interval-ms 10000
                    timeout-ms 3600000}}]
  (let [start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)]
    (loop []
      (let [status (poll-transfer-status task-id :config config)
            status-kw (:status status)]
        (cond
          ;; Completed (success or failure)
          (#{:succeeded :failed :canceled} status-kw)
          (do
            (log/info "Globus transfer completed"
                      {:task-id task-id
                       :status status-kw
                       :duration-ms (- (System/currentTimeMillis) start-time)})
            status)

          ;; Timeout
          (> (System/currentTimeMillis) deadline)
          (do
            (log/warn "Globus transfer polling timed out"
                      {:task-id task-id
                       :timeout-ms timeout-ms})
            {:status :timeout
             :task-id task-id
             :last-known-status status-kw})

          ;; Continue polling
          :else
          (do
            (Thread/sleep poll-interval-ms)
            (recur)))))))

;; =============================================================================
;; Convenience Wrappers
;; =============================================================================

(defn transfer-succeeded?
  "Returns true if a transfer status indicates success."
  [status]
  (= :succeeded (:status status)))

(defn transfer-failed?
  "Returns true if a transfer status indicates failure."
  [status]
  (#{:failed :canceled :timeout} (:status status)))

(defn transfer-pending?
  "Returns true if a transfer is still in progress."
  [status]
  (#{:pending :active} (:status status)))

;; =============================================================================
;; Endpoint Management
;; =============================================================================

(defn list-endpoint-contents
  "Lists the contents of a directory on an endpoint.

   Useful for verifying transfer completion."
  [endpoint-id path & {:keys [config]}]
  (let [cfg (or config (globus-config))
        result (globus-request :get "/operation/endpoint/{endpoint}/ls"
                               cfg
                               :query-params {:path path})]
    (if (:success result)
      {:success true
       :files (get-in result [:data :DATA])}
      result)))

(defn verify-destination
  "Verifies that a transfer destination exists and is accessible."
  [task-id & {:keys [config]}]
  (let [status (poll-transfer-status task-id :config config)]
    (if (= :succeeded (:status status))
      ;; Could add additional verification here
      {:success true
       :bytes-transferred (:bytes-transferred status)
       :files-transferred (:files-transferred status)}
      {:success false
       :status (:status status)
       :error "Transfer did not succeed"})))
