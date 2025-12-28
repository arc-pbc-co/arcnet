(ns arcnet.sim
  "ARCNet Load Testing Simulation Framework.

   This namespace provides a unified API for load testing ARCNet:

   Quick Start:
   ```clojure
   (require '[arcnet.sim :as sim])

   ;; List available scenarios
   (sim/print-scenarios!)

   ;; Run baseline test (5k RPS, no chaos, p99 < 100ms)
   (sim/run-baseline!)

   ;; Run stress test (10k RPS)
   (sim/run-stress!)

   ;; Run with chaos
   (sim/run-chaos-all!)

   ;; Custom scenario
   (sim/run-custom! {:rps 2000
                     :duration-seconds 120
                     :chaos-type :grid-failure})
   ```

   Components:
   - arcnet.sim.nodes - Node simulation (1000 nodes, solar curves, staleness)
   - arcnet.sim.load - Load generation (configurable RPS, Zipf distribution)
   - arcnet.sim.chaos - Chaos scenarios (grid failure, partitions, slowdowns)
   - arcnet.sim.stats - Statistics collection and assertions
   - arcnet.sim.runner - Main orchestrator"
  (:require [arcnet.sim.runner :as runner]
            [arcnet.sim.nodes :as nodes]
            [arcnet.sim.load :as load]
            [arcnet.sim.chaos :as chaos]
            [arcnet.sim.stats :as stats]))

;; =============================================================================
;; Main API - Re-exported from runner
;; =============================================================================

(def run-scenario!
  "Runs a predefined scenario.

   Available scenarios:
   - :baseline - 5k RPS, no chaos, p99 < 100ms
   - :stress - 10k RPS stress test
   - :light - 1k RPS quick validation
   - :chaos-grid - 5k RPS with grid failure
   - :chaos-partition - 5k RPS with Kafka partition delay
   - :chaos-xtdb - 5k RPS with XTDB slowdown
   - :chaos-all - 5k RPS with all chaos scenarios
   - :endurance - 5k RPS for 10 minutes with random chaos

   Returns a map with :stats, :report, and :assertions."
  runner/run-scenario!)

(def run-baseline!
  "Runs baseline performance test (5k RPS, no chaos).

   Assertions:
   - p99 latency < 100ms
   - Rejection rate < 5%"
  runner/run-baseline!)

(def run-stress!
  "Runs high load stress test (10k RPS, no chaos)."
  runner/run-stress!)

(def run-light!
  "Runs light load test (1k RPS, 30 seconds).

   Good for quick validation."
  runner/run-light!)

(def run-chaos-all!
  "Runs full chaos test with all scenarios active."
  runner/run-chaos-all!)

(def run-custom!
  "Runs a custom scenario.

   Options:
   - :rps - Requests per second (default 1000)
   - :duration-seconds - Test duration (default 60)
   - :node-count - Number of simulated nodes (default 1000)
   - :chaos-type - :grid-failure, :kafka-partition, :xtdb-slowdown, :all, or nil
   - :chaos-geozone - Target geozone for chaos
   - :chaos-delay-seconds - Delay before chaos starts
   - :chaos-duration-seconds - How long chaos lasts
   - :p99-threshold-ms - p99 latency assertion threshold
   - :on-request - Custom request handler function

   Example:
   (run-custom! {:rps 3000
                 :duration-seconds 120
                 :chaos-type :kafka-partition
                 :chaos-delay-seconds 30
                 :chaos-duration-seconds 60})"
  runner/run-custom!)

(def list-scenarios
  "Returns a map of available scenarios with their configurations."
  runner/list-scenarios)

(def print-scenarios!
  "Prints available scenarios to stdout."
  runner/print-scenarios!)

(def status
  "Returns current simulation status including nodes, load, chaos, and stats."
  runner/status)

(def stop!
  "Emergency stop - halts all simulation components."
  runner/stop!)

;; =============================================================================
;; Direct Component Access
;; =============================================================================

(def get-fleet-stats
  "Returns current node fleet statistics."
  nodes/get-fleet-stats)

(def get-active-nodes
  "Returns all non-stale nodes."
  nodes/get-active-nodes)

(def get-stats
  "Returns current scheduling statistics."
  stats/get-stats)

(def print-report!
  "Prints a formatted test report."
  stats/print-report!)

(def generate-report
  "Generates a comprehensive test report."
  stats/generate-report)

;; =============================================================================
;; Chaos Control (for manual testing)
;; =============================================================================

(def activate-grid-failure!
  "Manually activate grid failure chaos.

   Effects:
   - 30% of nodes switch to battery
   - 10% go offline"
  chaos/activate-grid-failure!)

(def deactivate-grid-failure!
  "Deactivate grid failure chaos."
  chaos/deactivate-grid-failure!)

(def activate-kafka-partition!
  "Manually activate Kafka partition chaos.

   Messages to target geozone delayed by 5 seconds."
  chaos/activate-kafka-partition!)

(def deactivate-kafka-partition!
  "Deactivate Kafka partition chaos."
  chaos/deactivate-kafka-partition!)

(def activate-xtdb-slowdown!
  "Manually activate XTDB slowdown chaos.

   Adds 500ms latency to all queries."
  chaos/activate-xtdb-slowdown!)

(def deactivate-xtdb-slowdown!
  "Deactivate XTDB slowdown chaos."
  chaos/deactivate-xtdb-slowdown!)

(def activate-all-chaos!
  "Activate all chaos scenarios at once."
  chaos/activate-all-chaos!)

(def deactivate-all-chaos!
  "Deactivate all chaos scenarios."
  chaos/deactivate-all-chaos!)

(def chaos-active?
  "Returns true if any chaos scenario is active."
  chaos/chaos-active?)

;; =============================================================================
;; Load Generation (for manual testing)
;; =============================================================================

(def generate-requests
  "Generate n requests without running the simulation.

   Useful for examining request distribution.

   Example:
   (def reqs (generate-requests 10000))
   (load/request-distribution reqs)"
  load/generate-requests)

(def request-distribution
  "Analyze the distribution of a batch of requests.

   Returns priority, model, and context token distributions."
  load/request-distribution)

;; =============================================================================
;; Assertions (for manual testing)
;; =============================================================================

(def assert-latency-p99!
  "Assert that p99 latency is under threshold.

   Default threshold is 100ms."
  stats/assert-latency-p99!)

(def assert-rejection-rate!
  "Assert that rejection rate is under threshold.

   Default threshold is 5%."
  stats/assert-rejection-rate!)

(def assert-graceful-degradation!
  "Assert that system degrades gracefully under chaos.

   Verifies:
   - All requests accounted for (no crashes)
   - Rejection rate < 50%
   - Some successes still occur"
  stats/assert-graceful-degradation!)

(def run-all-assertions!
  "Run all assertions and return results."
  stats/run-all-assertions!)
