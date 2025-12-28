(ns arcnet.state.queries
  "XTDB queries for ARCNet regional state.

   Provides query functions for:
   - Finding available nodes for inference scheduling
   - Historical node state via XTDB bitemporality
   - Regional aggregations for the central tier

   All queries leverage XTDB v2's SQL interface."
  (:require [clojure.tools.logging :as log]
            [arcnet.state.regional :as regional]
            [arcnet.observability.metrics :as metrics]
            [arcnet.observability.tracing :as tracing])
  (:import [java.time Instant Duration]
           [java.util UUID Date]))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn- execute-query
  "Executes an XTDB SQL query with metrics and tracing."
  [query-type sql params]
  (let [node (regional/get-node)
        timer (metrics/start-timer)]
    (tracing/with-xtdb-span {:query-type query-type :query sql}
      (try
        (let [result (if params
                       (.query node sql (object-array params))
                       (.query node sql))]
          (metrics/record-xtdb-query!
           {:query-type query-type
            :duration-ms (timer)
            :success? true})
          ;; Convert result set to sequence of maps
          (into [] (iterator-seq (.iterator result))))
        (catch Exception e
          (metrics/record-xtdb-query!
           {:query-type query-type
            :duration-ms (timer)
            :success? false})
          (log/error e "Query failed" {:type query-type :sql sql})
          (throw e))))))

(defn- instant->sql-timestamp
  "Converts a Java Instant to a SQL-compatible timestamp string."
  [^Instant instant]
  (.toString instant))

;; =============================================================================
;; Node Availability Queries
;; =============================================================================

(defn find-available-nodes
  "Finds nodes available for inference in a geozone.

   Criteria:
   - In the specified geozone (geohash prefix match)
   - Has the specified model loaded
   - Battery level above minimum threshold
   - GPU utilization below 85%
   - Last seen within 30 seconds (not stale)

   Parameters:
   - geozone: Geozone identifier or geohash prefix
   - model-id: Model that must be loaded
   - min-battery: Minimum battery level (0.0-1.0)

   Returns a sequence of node documents sorted by GPU utilization (ascending)."
  [geozone model-id min-battery]
  {:pre [(string? geozone)
         (string? model-id)
         (number? min-battery)
         (<= 0.0 min-battery 1.0)]}
  (log/debug "Finding available nodes"
             {:geozone geozone :model model-id :min-battery min-battery})
  (let [staleness-cutoff (regional/staleness-cutoff)
        ;; XTDB v2 SQL query
        sql "SELECT *
             FROM nodes
             WHERE node_geohash LIKE ?
               AND ? = ANY(node_models_loaded)
               AND node_battery_level >= ?
               AND node_gpu_utilization < 0.85
               AND node_last_seen >= ?
             ORDER BY node_gpu_utilization ASC"
        params [(str geozone "%")
                model-id
                min-battery
                (instant->sql-timestamp staleness-cutoff)]
        results (execute-query "find-available-nodes" sql params)]
    (log/debug "Found available nodes" {:count (count results)})
    results))

(defn find-nodes-by-geohash
  "Finds all nodes in a geohash region.

   Parameters:
   - geohash-prefix: Geohash prefix for spatial filtering
   - include-stale?: Whether to include stale nodes (default false)

   Returns a sequence of node documents."
  [geohash-prefix & {:keys [include-stale?] :or {include-stale? false}}]
  {:pre [(string? geohash-prefix)]}
  (let [sql (if include-stale?
              "SELECT * FROM nodes WHERE node_geohash LIKE ? ORDER BY node_last_seen DESC"
              "SELECT * FROM nodes WHERE node_geohash LIKE ? AND node_last_seen >= ? ORDER BY node_last_seen DESC")
        params (if include-stale?
                 [(str geohash-prefix "%")]
                 [(str geohash-prefix "%")
                  (instant->sql-timestamp (regional/staleness-cutoff))])]
    (execute-query "find-nodes-by-geohash" sql params)))

(defn find-nodes-with-model
  "Finds all nodes that have a specific model loaded.

   Parameters:
   - model-id: Model identifier to search for
   - include-stale?: Whether to include stale nodes (default false)

   Returns a sequence of node documents."
  [model-id & {:keys [include-stale?] :or {include-stale? false}}]
  {:pre [(string? model-id)]}
  (let [sql (if include-stale?
              "SELECT * FROM nodes WHERE ? = ANY(node_models_loaded)"
              "SELECT * FROM nodes WHERE ? = ANY(node_models_loaded) AND node_last_seen >= ?")
        params (if include-stale?
                 [model-id]
                 [model-id (instant->sql-timestamp (regional/staleness-cutoff))])]
    (execute-query "find-nodes-with-model" sql params)))

(defn find-solar-nodes
  "Finds nodes powered by solar energy.

   Parameters:
   - min-battery: Minimum battery level (default 0.0)
   - include-stale?: Whether to include stale nodes (default false)

   Returns a sequence of node documents."
  [& {:keys [min-battery include-stale?]
      :or {min-battery 0.0 include-stale? false}}]
  (let [base-sql "SELECT * FROM nodes WHERE node_energy_source = 'solar' AND node_battery_level >= ?"
        sql (if include-stale?
              base-sql
              (str base-sql " AND node_last_seen >= ?"))
        params (if include-stale?
                 [min-battery]
                 [min-battery (instant->sql-timestamp (regional/staleness-cutoff))])]
    (execute-query "find-solar-nodes" sql params)))

;; =============================================================================
;; Historical Queries (Bitemporality)
;; =============================================================================

(defn node-history
  "Returns the historical state of a node over a time range.

   Leverages XTDB's bitemporality to retrieve past states.

   Parameters:
   - node-id: Node UUID
   - from-time: Start of time range (Instant or Date)
   - to-time: End of time range (Instant or Date)

   Returns a sequence of historical node states with timestamps."
  [node-id from-time to-time]
  {:pre [(uuid? node-id)]}
  (log/debug "Querying node history"
             {:node-id node-id :from from-time :to to-time})
  (let [from-instant (if (instance? Instant from-time)
                       from-time
                       (.toInstant from-time))
        to-instant (if (instance? Instant to-time)
                     to-time
                     (.toInstant to-time))
        ;; XTDB v2 temporal query using SYSTEM_TIME
        sql "SELECT *, _system_from, _system_to
             FROM nodes
             FOR SYSTEM_TIME BETWEEN ? AND ?
             WHERE xt$id = ?
             ORDER BY _system_from ASC"
        params [(instant->sql-timestamp from-instant)
                (instant->sql-timestamp to-instant)
                node-id]]
    (execute-query "node-history" sql params)))

(defn node-state-at
  "Returns the state of a node at a specific point in time.

   Parameters:
   - node-id: Node UUID
   - as-of: Point in time (Instant or Date)

   Returns the node document as it existed at that time, or nil."
  [node-id as-of]
  {:pre [(uuid? node-id)]}
  (let [as-of-instant (if (instance? Instant as-of)
                        as-of
                        (.toInstant as-of))
        sql "SELECT *
             FROM nodes
             FOR SYSTEM_TIME AS OF ?
             WHERE xt$id = ?"
        params [(instant->sql-timestamp as-of-instant)
                node-id]
        results (execute-query "node-state-at" sql params)]
    (first results)))

;; =============================================================================
;; Aggregation Queries
;; =============================================================================

(defn count-active-nodes
  "Returns the count of active (non-stale) nodes.

   Optional filters:
   - :geozone - Filter by geozone/geohash prefix
   - :energy-source - Filter by energy source"
  [& {:keys [geozone energy-source]}]
  (let [conditions ["node_last_seen >= ?"]
        params [(instant->sql-timestamp (regional/staleness-cutoff))]
        conditions (cond-> conditions
                     geozone (conj "node_geohash LIKE ?")
                     energy-source (conj "node_energy_source = ?"))
        params (cond-> params
                 geozone (conj (str geozone "%"))
                 energy-source (conj (name energy-source)))
        sql (str "SELECT COUNT(*) as count FROM nodes WHERE "
                 (clojure.string/join " AND " conditions))
        results (execute-query "count-active-nodes" sql params)]
    (or (:count (first results)) 0)))

(defn aggregate-battery-levels
  "Computes battery level statistics for active nodes.

   Returns:
   {:avg average-battery
    :min minimum-battery
    :max maximum-battery
    :count node-count}"
  [& {:keys [geozone]}]
  (let [sql (if geozone
              "SELECT AVG(node_battery_level) as avg,
                      MIN(node_battery_level) as min,
                      MAX(node_battery_level) as max,
                      COUNT(*) as count
               FROM nodes
               WHERE node_last_seen >= ? AND node_geohash LIKE ?"
              "SELECT AVG(node_battery_level) as avg,
                      MIN(node_battery_level) as min,
                      MAX(node_battery_level) as max,
                      COUNT(*) as count
               FROM nodes
               WHERE node_last_seen >= ?")
        params (if geozone
                 [(instant->sql-timestamp (regional/staleness-cutoff))
                  (str geozone "%")]
                 [(instant->sql-timestamp (regional/staleness-cutoff))])
        results (execute-query "aggregate-battery-levels" sql params)]
    (first results)))

(defn aggregate-gpu-utilization
  "Computes GPU utilization statistics for active nodes.

   Returns:
   {:avg average-utilization
    :min minimum-utilization
    :max maximum-utilization
    :count node-count
    :available-count count-with-util-below-85}"
  [& {:keys [geozone]}]
  (let [base-where "node_last_seen >= ?"
        geozone-where (when geozone " AND node_geohash LIKE ?")
        sql (str "SELECT AVG(node_gpu_utilization) as avg,
                         MIN(node_gpu_utilization) as min,
                         MAX(node_gpu_utilization) as max,
                         COUNT(*) as count,
                         SUM(CASE WHEN node_gpu_utilization < 0.85 THEN 1 ELSE 0 END) as available_count
                  FROM nodes
                  WHERE " base-where geozone-where)
        params (if geozone
                 [(instant->sql-timestamp (regional/staleness-cutoff))
                  (str geozone "%")]
                 [(instant->sql-timestamp (regional/staleness-cutoff))])
        results (execute-query "aggregate-gpu-utilization" sql params)]
    (first results)))

(defn count-by-energy-source
  "Counts active nodes grouped by energy source.

   Returns a map of energy-source -> count."
  [& {:keys [geozone]}]
  (let [sql (if geozone
              "SELECT node_energy_source, COUNT(*) as count
               FROM nodes
               WHERE node_last_seen >= ? AND node_geohash LIKE ?
               GROUP BY node_energy_source"
              "SELECT node_energy_source, COUNT(*) as count
               FROM nodes
               WHERE node_last_seen >= ?
               GROUP BY node_energy_source")
        params (if geozone
                 [(instant->sql-timestamp (regional/staleness-cutoff))
                  (str geozone "%")]
                 [(instant->sql-timestamp (regional/staleness-cutoff))])
        results (execute-query "count-by-energy-source" sql params)]
    (into {} (map (fn [r] [(keyword (:node_energy_source r)) (:count r)]) results))))

;; =============================================================================
;; Node Lookup
;; =============================================================================

(defn get-node
  "Retrieves a single node by ID.

   Returns the node document or nil if not found."
  [node-id]
  {:pre [(uuid? node-id)]}
  (let [sql "SELECT * FROM nodes WHERE xt$id = ?"
        results (execute-query "get-node" sql [node-id])]
    (first results)))

(defn get-nodes
  "Retrieves multiple nodes by IDs.

   Returns a map of node-id -> node document."
  [node-ids]
  {:pre [(every? uuid? node-ids)]}
  (when (seq node-ids)
    (let [placeholders (clojure.string/join ", " (repeat (count node-ids) "?"))
          sql (str "SELECT * FROM nodes WHERE xt$id IN (" placeholders ")")
          results (execute-query "get-nodes" sql (vec node-ids))]
      (into {} (map (fn [r] [(:xt/id r) r]) results)))))

;; =============================================================================
;; Document Count for Metrics
;; =============================================================================

(defn total-doc-count
  "Returns the total number of node documents in XTDB."
  []
  (let [sql "SELECT COUNT(*) as count FROM nodes"
        results (execute-query "total-doc-count" sql nil)]
    (or (:count (first results)) 0)))

(defn update-doc-count-metrics!
  "Updates Prometheus metrics with current document counts."
  []
  (metrics/set-xtdb-doc-count! {:doc-type "nodes"
                                :count (total-doc-count)})
  (metrics/set-xtdb-doc-count! {:doc-type "active-nodes"
                                :count (count-active-nodes)}))
