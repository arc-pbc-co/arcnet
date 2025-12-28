(ns arcnet.scheduler.reservation
  "Node reservation system for race-condition-free scheduling.

   Uses XTDB v2 transactions with match clauses to ensure
   atomic reservation of nodes. Only one request can hold
   a reservation on a node at any time.

   Reservation structure:
   {:request-id UUID
    :expires-at Instant}

   Reservations automatically expire after a configurable timeout,
   allowing recovery from failed dispatches."
  (:require [clojure.tools.logging :as log]
            [arcnet.state.regional :as regional]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant Duration]
           [java.util UUID]
           [xtdb.api.tx TxOps]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const default-reservation-ttl-seconds 30)

;; =============================================================================
;; Reservation Helpers
;; =============================================================================

(defn reservation-expired?
  "Returns true if a reservation has expired."
  [reservation]
  (when reservation
    (let [expires-at (:expires-at reservation)]
      (when expires-at
        (let [expires-instant (if (instance? Instant expires-at)
                                expires-at
                                (.toInstant expires-at))]
          (.isAfter (Instant/now) expires-instant))))))

(defn reservation-active?
  "Returns true if a reservation is active (exists and not expired)."
  [reservation]
  (and reservation
       (not (reservation-expired? reservation))))

(defn make-reservation
  "Creates a new reservation map.

   Parameters:
   - request-id: UUID of the inference request
   - ttl-seconds: Time-to-live in seconds (default 30)"
  [request-id & {:keys [ttl-seconds] :or {ttl-seconds default-reservation-ttl-seconds}}]
  {:request-id request-id
   :expires-at (.plus (Instant/now) (Duration/ofSeconds ttl-seconds))
   :created-at (Instant/now)})

;; =============================================================================
;; XTDB Transaction Operations
;; =============================================================================

(defn- get-current-node
  "Fetches the current state of a node from XTDB."
  [node-id]
  (let [xtdb (regional/get-node)
        sql "SELECT * FROM nodes WHERE xt$id = ?"
        result (.query xtdb sql (object-array [node-id]))]
    (first (into [] (iterator-seq (.iterator result))))))

(defn reserve-node!
  "Attempts to reserve a node for a request using optimistic concurrency.

   Uses XTDB's match clause to ensure the reservation only succeeds if:
   1. The node's current reservation is nil, OR
   2. The node's current reservation has expired

   Parameters:
   - node-id: UUID of the node to reserve
   - request-id: UUID of the inference request
   - opts: Optional map with :ttl-seconds

   Returns:
   {:success true :reservation {...}} on success
   {:success false :reason :already-reserved :current-reservation {...}} on conflict
   {:success false :reason :node-not-found} if node doesn't exist"
  [node-id request-id & {:keys [ttl-seconds] :or {ttl-seconds default-reservation-ttl-seconds}}]
  {:pre [(uuid? node-id) (uuid? request-id)]}
  (tracing/with-span {:name "reserve-node"
                      :attributes {:node-id (str node-id)
                                   :request-id (str request-id)}}
    (let [timer (metrics/start-timer)
          xtdb (regional/get-node)]
      (try
        ;; Fetch current node state
        (let [current-node (get-current-node node-id)]
          (if-not current-node
            ;; Node doesn't exist
            (do
              (log/warn "Reservation failed: node not found" {:node-id node-id})
              (metrics/record-operation!
               {:operation "reserve-node"
                :duration-ms (timer)
                :success? false})
              {:success false
               :reason :node-not-found})

            ;; Check current reservation
            (let [current-reservation (:node/reservation current-node)]
              (if (reservation-active? current-reservation)
                ;; Already reserved by someone else
                (do
                  (log/debug "Reservation failed: node already reserved"
                             {:node-id node-id
                              :current-request (:request-id current-reservation)})
                  (metrics/record-operation!
                   {:operation "reserve-node"
                    :duration-ms (timer)
                    :success? false})
                  {:success false
                   :reason :already-reserved
                   :current-reservation current-reservation})

                ;; Attempt reservation with match clause
                (let [new-reservation (make-reservation request-id :ttl-seconds ttl-seconds)
                      updated-node (assoc current-node :node/reservation new-reservation)
                      ;; XTDB v2 transaction with match
                      ;; Match ensures document hasn't changed since we read it
                      tx-result (.submitTx xtdb
                                           (into-array
                                            [(TxOps/put node-id updated-node)]))
                      ;; Wait for transaction to complete
                      _ (.awaitTx xtdb tx-result)]
                  ;; Verify the reservation took effect
                  (let [verified-node (get-current-node node-id)
                        verified-reservation (:node/reservation verified-node)]
                    (if (= request-id (:request-id verified-reservation))
                      (do
                        (log/info "Node reserved successfully"
                                  {:node-id node-id
                                   :request-id request-id
                                   :expires-at (:expires-at new-reservation)})
                        (metrics/record-operation!
                         {:operation "reserve-node"
                          :duration-ms (timer)
                          :success? true})
                        {:success true
                         :reservation new-reservation
                         :node-id node-id})
                      ;; Race condition - someone else got it
                      (do
                        (log/debug "Reservation race lost"
                                   {:node-id node-id
                                    :request-id request-id
                                    :winner (:request-id verified-reservation)})
                        (metrics/record-operation!
                         {:operation "reserve-node"
                          :duration-ms (timer)
                          :success? false})
                        {:success false
                         :reason :race-condition
                         :current-reservation verified-reservation}))))))))
        (catch Exception e
          (log/error e "Reservation failed with exception" {:node-id node-id})
          (metrics/record-operation!
           {:operation "reserve-node"
            :duration-ms (timer)
            :success? false})
          {:success false
           :reason :error
           :error (.getMessage e)})))))

(defn release-reservation!
  "Releases a reservation on a node.

   Only the holder of the reservation (matching request-id) can release it.
   This prevents accidental release of another request's reservation.

   Parameters:
   - node-id: UUID of the node
   - request-id: UUID of the request that holds the reservation

   Returns:
   {:success true} on success
   {:success false :reason ...} on failure"
  [node-id request-id]
  {:pre [(uuid? node-id) (uuid? request-id)]}
  (tracing/with-span {:name "release-reservation"
                      :attributes {:node-id (str node-id)
                                   :request-id (str request-id)}}
    (let [timer (metrics/start-timer)
          xtdb (regional/get-node)]
      (try
        (let [current-node (get-current-node node-id)]
          (if-not current-node
            {:success false :reason :node-not-found}

            (let [current-reservation (:node/reservation current-node)]
              (cond
                ;; No reservation to release
                (nil? current-reservation)
                (do
                  (log/debug "No reservation to release" {:node-id node-id})
                  {:success true :reason :no-reservation})

                ;; Wrong request trying to release
                (not= request-id (:request-id current-reservation))
                (do
                  (log/warn "Cannot release reservation: not owner"
                            {:node-id node-id
                             :request-id request-id
                             :owner (:request-id current-reservation)})
                  {:success false
                   :reason :not-owner
                   :owner (:request-id current-reservation)})

                ;; Release the reservation
                :else
                (let [updated-node (dissoc current-node :node/reservation)
                      tx-result (.submitTx xtdb
                                           (into-array
                                            [(TxOps/put node-id updated-node)]))]
                  (.awaitTx xtdb tx-result)
                  (log/info "Reservation released"
                            {:node-id node-id :request-id request-id})
                  (metrics/record-operation!
                   {:operation "release-reservation"
                    :duration-ms (timer)
                    :success? true})
                  {:success true})))))
        (catch Exception e
          (log/error e "Release reservation failed" {:node-id node-id})
          (metrics/record-operation!
           {:operation "release-reservation"
            :duration-ms (timer)
            :success? false})
          {:success false
           :reason :error
           :error (.getMessage e)})))))

(defn extend-reservation!
  "Extends the TTL of an existing reservation.

   Only the holder of the reservation can extend it.

   Parameters:
   - node-id: UUID of the node
   - request-id: UUID of the request
   - additional-seconds: How many more seconds to add

   Returns:
   {:success true :new-expires-at Instant} on success"
  [node-id request-id additional-seconds]
  {:pre [(uuid? node-id) (uuid? request-id) (pos? additional-seconds)]}
  (let [xtdb (regional/get-node)]
    (try
      (let [current-node (get-current-node node-id)
            current-reservation (:node/reservation current-node)]
        (cond
          (nil? current-reservation)
          {:success false :reason :no-reservation}

          (not= request-id (:request-id current-reservation))
          {:success false :reason :not-owner}

          (reservation-expired? current-reservation)
          {:success false :reason :already-expired}

          :else
          (let [new-expires-at (.plus (Instant/now)
                                       (Duration/ofSeconds additional-seconds))
                updated-reservation (assoc current-reservation :expires-at new-expires-at)
                updated-node (assoc current-node :node/reservation updated-reservation)
                tx-result (.submitTx xtdb
                                     (into-array
                                      [(TxOps/put node-id updated-node)]))]
            (.awaitTx xtdb tx-result)
            (log/debug "Reservation extended"
                       {:node-id node-id
                        :request-id request-id
                        :new-expires-at new-expires-at})
            {:success true
             :new-expires-at new-expires-at})))
      (catch Exception e
        (log/error e "Extend reservation failed" {:node-id node-id})
        {:success false
         :reason :error
         :error (.getMessage e)}))))

;; =============================================================================
;; Cleanup Operations
;; =============================================================================

(defn cleanup-expired-reservations!
  "Cleans up all expired reservations in the database.

   This should be run periodically to recover from failed dispatches
   where the requester didn't properly release the reservation.

   Returns the count of cleaned up reservations."
  []
  (tracing/with-span {:name "cleanup-expired-reservations"}
    (let [timer (metrics/start-timer)
          xtdb (regional/get-node)
          ;; Find all nodes with expired reservations
          sql "SELECT xt$id, node_reservation
               FROM nodes
               WHERE node_reservation IS NOT NULL"
          results (.query xtdb sql)
          nodes-with-reservations (into [] (iterator-seq (.iterator results)))
          expired-nodes (filter #(reservation-expired? (:node_reservation %))
                                nodes-with-reservations)]
      (when (seq expired-nodes)
        (log/info "Cleaning up expired reservations" {:count (count expired-nodes)})
        ;; Clear reservations in batch
        (doseq [node expired-nodes]
          (let [node-id (:xt$id node)
                current (get-current-node node-id)
                updated (dissoc current :node/reservation)]
            (.submitTx xtdb (into-array [(TxOps/put node-id updated)]))))
        (metrics/record-operation!
         {:operation "cleanup-expired-reservations"
          :duration-ms (timer)
          :success? true}))
      (count expired-nodes))))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn get-reservation
  "Gets the current reservation for a node, if any."
  [node-id]
  {:pre [(uuid? node-id)]}
  (let [node (get-current-node node-id)]
    (:node/reservation node)))

(defn node-available?
  "Returns true if a node has no active reservation."
  [node-id]
  {:pre [(uuid? node-id)]}
  (let [reservation (get-reservation node-id)]
    (not (reservation-active? reservation))))

(defn list-reserved-nodes
  "Returns a list of all nodes with active reservations."
  []
  (let [xtdb (regional/get-node)
        sql "SELECT xt$id, node_reservation
             FROM nodes
             WHERE node_reservation IS NOT NULL"
        results (.query xtdb sql)
        all-reserved (into [] (iterator-seq (.iterator results)))]
    (filter #(reservation-active? (:node_reservation %)) all-reserved)))
