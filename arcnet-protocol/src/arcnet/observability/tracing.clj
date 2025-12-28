(ns arcnet.observability.tracing
  "OpenTelemetry distributed tracing for ARCNet.

   Provides:
   - Trace ID generation and propagation
   - Kafka header propagation
   - XTDB query span wrapping
   - Context management utilities"
  (:require [clojure.tools.logging :as log])
  (:import [io.opentelemetry.api GlobalOpenTelemetry OpenTelemetry]
           [io.opentelemetry.api.trace Span SpanBuilder SpanKind StatusCode Tracer]
           [io.opentelemetry.api.common AttributeKey Attributes]
           [io.opentelemetry.context Context Scope]
           [io.opentelemetry.sdk OpenTelemetrySdk]
           [io.opentelemetry.sdk.trace SdkTracerProvider]
           [io.opentelemetry.sdk.trace.export BatchSpanProcessor SimpleSpanProcessor]
           [io.opentelemetry.sdk.resources Resource]
           [io.opentelemetry.exporter.otlp.trace OtlpGrpcSpanExporter]
           [io.opentelemetry.semconv.resource.attributes ResourceAttributes]
           [java.util.concurrent TimeUnit]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:const service-name "arcnet")
(def ^:const header-trace-id "arcnet-trace-id")
(def ^:const header-span-id "arcnet-span-id")
(def ^:const header-trace-flags "arcnet-trace-flags")

;; =============================================================================
;; OpenTelemetry Initialization
;; =============================================================================

(defonce ^:private otel-instance (atom nil))
(defonce ^:private tracer-instance (atom nil))

(defn- build-resource
  "Builds the OpenTelemetry resource with service info."
  [service-name service-version node-id geozone-id]
  (-> (Resource/getDefault)
      (.merge
       (Resource/create
        (-> (Attributes/builder)
            (.put ResourceAttributes/SERVICE_NAME service-name)
            (.put ResourceAttributes/SERVICE_VERSION (or service-version "unknown"))
            (.put (AttributeKey/stringKey "arcnet.node.id") (or node-id "unknown"))
            (.put (AttributeKey/stringKey "arcnet.geozone.id") (or geozone-id "unknown"))
            (.build))))))

(defn init!
  "Initializes OpenTelemetry with OTLP exporter.

   Options:
   - :service-name - Service name (default 'arcnet')
   - :service-version - Service version
   - :node-id - ARCNet node identifier
   - :geozone-id - ARCNet geozone identifier
   - :otlp-endpoint - OTLP collector endpoint (default 'http://localhost:4317')
   - :export-interval-ms - Batch export interval (default 5000)"
  ([] (init! {}))
  ([{:keys [service-name service-version node-id geozone-id
            otlp-endpoint export-interval-ms]
     :or {service-name "arcnet"
          otlp-endpoint "http://localhost:4317"
          export-interval-ms 5000}}]
   (when @otel-instance
     (log/warn "OpenTelemetry already initialized"))
   (let [resource (build-resource service-name service-version node-id geozone-id)
         exporter (-> (OtlpGrpcSpanExporter/builder)
                      (.setEndpoint otlp-endpoint)
                      (.setTimeout 10 TimeUnit/SECONDS)
                      (.build))
         processor (-> (BatchSpanProcessor/builder exporter)
                       (.setScheduleDelay export-interval-ms TimeUnit/MILLISECONDS)
                       (.build))
         tracer-provider (-> (SdkTracerProvider/builder)
                             (.addSpanProcessor processor)
                             (.setResource resource)
                             (.build))
         otel (-> (OpenTelemetrySdk/builder)
                  (.setTracerProvider tracer-provider)
                  (.build))]
     (reset! otel-instance otel)
     (reset! tracer-instance (.getTracer otel service-name))
     (log/info "OpenTelemetry initialized"
               {:service service-name
                :endpoint otlp-endpoint})
     otel)))

(defn init-noop!
  "Initializes a no-op tracer for testing/development."
  []
  (reset! otel-instance (GlobalOpenTelemetry/get))
  (reset! tracer-instance (.getTracer (GlobalOpenTelemetry/get) service-name))
  (log/info "OpenTelemetry initialized with no-op tracer"))

(defn shutdown!
  "Shuts down OpenTelemetry, flushing any pending spans."
  []
  (when-let [otel @otel-instance]
    (when (instance? OpenTelemetrySdk otel)
      (.close ^OpenTelemetrySdk otel))
    (reset! otel-instance nil)
    (reset! tracer-instance nil)
    (log/info "OpenTelemetry shut down")))

(defn get-tracer
  "Returns the configured tracer."
  ^Tracer []
  (or @tracer-instance
      (do (init-noop!)
          @tracer-instance)))

;; =============================================================================
;; Trace ID Generation
;; =============================================================================

(defn generate-trace-id
  "Generates a new W3C-compatible trace ID (32 hex chars)."
  []
  (let [bytes (byte-array 16)
        _ (.nextBytes (java.security.SecureRandom.) bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn generate-span-id
  "Generates a new span ID (16 hex chars)."
  []
  (let [bytes (byte-array 8)
        _ (.nextBytes (java.security.SecureRandom.) bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

;; =============================================================================
;; Span Creation
;; =============================================================================

(defn start-span
  "Starts a new span with the given name.

   Options:
   - :kind - SpanKind (default :internal)
   - :parent - Parent context or span
   - :attributes - Map of attributes to add

   Returns the span (must be ended with end-span!)."
  ^Span [span-name & {:keys [kind parent attributes]
                      :or {kind :internal}}]
  (let [tracer (get-tracer)
        span-kind (case kind
                    :internal SpanKind/INTERNAL
                    :server SpanKind/SERVER
                    :client SpanKind/CLIENT
                    :producer SpanKind/PRODUCER
                    :consumer SpanKind/CONSUMER
                    SpanKind/INTERNAL)
        builder (-> tracer
                    (.spanBuilder span-name)
                    (.setSpanKind span-kind))]
    ;; Set parent if provided
    (when parent
      (if (instance? Context parent)
        (.setParent builder ^Context parent)
        (.setParent builder (Context/current))))
    ;; Add attributes
    (doseq [[k v] attributes]
      (let [key-name (if (keyword? k) (name k) (str k))]
        (cond
          (string? v) (.setAttribute builder (AttributeKey/stringKey key-name) v)
          (integer? v) (.setAttribute builder (AttributeKey/longKey key-name) (long v))
          (float? v) (.setAttribute builder (AttributeKey/doubleKey key-name) (double v))
          (boolean? v) (.setAttribute builder (AttributeKey/booleanKey key-name) v)
          :else (.setAttribute builder (AttributeKey/stringKey key-name) (str v)))))
    (.startSpan builder)))

(defn end-span!
  "Ends a span, optionally recording an error."
  ([^Span span]
   (.end span))
  ([^Span span ^Throwable error]
   (-> span
       (.setStatus StatusCode/ERROR (.getMessage error))
       (.recordException error))
   (.end span)))

(defn set-attribute!
  "Sets an attribute on the current span."
  [^Span span key value]
  (let [key-name (if (keyword? key) (name key) (str key))]
    (cond
      (string? value)
      (.setAttribute span (AttributeKey/stringKey key-name) value)
      (integer? value)
      (.setAttribute span (AttributeKey/longKey key-name) (long value))
      (float? value)
      (.setAttribute span (AttributeKey/doubleKey key-name) (double value))
      (boolean? value)
      (.setAttribute span (AttributeKey/booleanKey key-name) value)
      :else
      (.setAttribute span (AttributeKey/stringKey key-name) (str value))))
  span)

(defn add-event!
  "Adds an event to the span."
  [^Span span event-name & {:keys [attributes]}]
  (if attributes
    (let [attrs-builder (Attributes/builder)]
      (doseq [[k v] attributes]
        (.put attrs-builder (AttributeKey/stringKey (name k)) (str v)))
      (.addEvent span event-name (.build attrs-builder)))
    (.addEvent span event-name))
  span)

;; =============================================================================
;; Context Management
;; =============================================================================

(defn current-span
  "Returns the current span from context."
  ^Span []
  (Span/current))

(defn current-context
  "Returns the current context."
  ^Context []
  (Context/current))

(defn with-span-context
  "Returns a new context with the span as current."
  ^Context [^Span span]
  (.with (Context/current) span))

(defn make-context-current!
  "Makes the context current and returns a Scope (must be closed)."
  ^Scope [^Context ctx]
  (.makeCurrent ctx))

;; =============================================================================
;; Kafka Header Propagation
;; =============================================================================

(defn inject-trace-headers
  "Injects trace context into a header map for Kafka propagation.

   Returns updated header map with trace-id, span-id, and flags."
  [headers ^Span span]
  (let [span-ctx (.getSpanContext span)]
    (assoc headers
           header-trace-id (.getTraceId span-ctx)
           header-span-id (.getSpanId span-ctx)
           header-trace-flags (if (.isSampled span-ctx) "01" "00"))))

(defn extract-trace-headers
  "Extracts trace context from Kafka headers.

   Returns {:trace-id \"...\" :span-id \"...\" :sampled? bool} or nil."
  [headers]
  (when-let [trace-id (get headers (keyword header-trace-id))]
    {:trace-id trace-id
     :span-id (get headers (keyword header-span-id))
     :sampled? (= "01" (get headers (keyword header-trace-flags)))}))

(defn start-span-from-headers
  "Starts a span using trace context from Kafka headers as parent.

   This creates a linked span that continues the distributed trace."
  ^Span [span-name headers & {:keys [kind attributes]
                              :or {kind :consumer}}]
  (let [trace-ctx (extract-trace-headers headers)
        span (start-span span-name :kind kind :attributes attributes)]
    ;; Add link to parent trace if available
    (when trace-ctx
      (set-attribute! span "arcnet.parent.trace_id" (:trace-id trace-ctx))
      (set-attribute! span "arcnet.parent.span_id" (:span-id trace-ctx)))
    span))

;; =============================================================================
;; with-span Macro
;; =============================================================================

(defmacro with-span
  "Executes body within a new span, handling errors automatically.

   Options:
   - :name - Span name (required)
   - :kind - SpanKind keyword (default :internal)
   - :attributes - Map of initial attributes

   Example:
   (with-span {:name \"process-message\" :kind :consumer}
     (process msg))"
  [{:keys [name kind attributes] :or {kind :internal}} & body]
  `(let [span# (start-span ~name :kind ~kind :attributes ~attributes)]
     (try
       (with-open [_scope# (make-context-current! (with-span-context span#))]
         (let [result# (do ~@body)]
           (end-span! span#)
           result#))
       (catch Throwable t#
         (end-span! span# t#)
         (throw t#)))))

(defmacro with-child-span
  "Creates a child span of the current span.

   Example:
   (with-span {:name \"parent\"}
     (with-child-span {:name \"child\"}
       (do-work)))"
  [{:keys [name kind attributes] :or {kind :internal}} & body]
  `(let [parent# (current-context)
         span# (start-span ~name :kind ~kind :parent parent# :attributes ~attributes)]
     (try
       (with-open [_scope# (make-context-current! (with-span-context span#))]
         (let [result# (do ~@body)]
           (end-span! span#)
           result#))
       (catch Throwable t#
         (end-span! span# t#)
         (throw t#)))))

;; =============================================================================
;; XTDB Query Wrapping
;; =============================================================================

(defmacro with-xtdb-span
  "Wraps an XTDB query with a tracing span.

   Options:
   - :query-type - Type of query (e.g., 'entity', 'q', 'pull')
   - :query - The query (for logging, sanitized)

   Example:
   (with-xtdb-span {:query-type \"q\" :query '{:find [e] :where [[e :type :user]]}}
     (xt/q db query))"
  [{:keys [query-type query]} & body]
  `(with-span {:name (str "xtdb." ~query-type)
               :kind :client
               :attributes {:db.system "xtdb"
                            :db.operation ~query-type
                            :db.statement (pr-str ~query)}}
     ~@body))

;; =============================================================================
;; Kafka Producer/Consumer Span Helpers
;; =============================================================================

(defmacro with-producer-span
  "Wraps a Kafka produce operation with a tracing span.

   Example:
   (with-producer-span {:topic \"events\" :schema \"NodeTelemetry\"}
     (.send producer record))"
  [{:keys [topic schema]} & body]
  `(with-span {:name (str "kafka.produce." ~topic)
               :kind :producer
               :attributes {:messaging.system "kafka"
                            :messaging.destination ~topic
                            :messaging.destination_kind "topic"
                            :arcnet.schema ~schema}}
     ~@body))

(defmacro with-consumer-span
  "Wraps a Kafka consume operation with a tracing span.

   If headers contain trace context, it will be linked.

   Example:
   (with-consumer-span {:topic \"events\" :schema \"NodeTelemetry\" :headers {}}
     (process-message msg))"
  [{:keys [topic schema headers]} & body]
  `(let [trace-ctx# (extract-trace-headers ~headers)
         span# (start-span (str "kafka.consume." ~topic)
                           :kind :consumer
                           :attributes {:messaging.system "kafka"
                                        :messaging.destination ~topic
                                        :messaging.destination_kind "topic"
                                        :arcnet.schema ~schema})]
     (when trace-ctx#
       (set-attribute! span# "arcnet.parent.trace_id" (:trace-id trace-ctx#)))
     (try
       (with-open [_scope# (make-context-current! (with-span-context span#))]
         (let [result# (do ~@body)]
           (end-span! span#)
           result#))
       (catch Throwable t#
         (end-span! span# t#)
         (throw t#)))))

;; =============================================================================
;; Trace ID for Request Correlation
;; =============================================================================

(def ^:dynamic *trace-id*
  "Dynamic var holding the current trace ID for logging correlation."
  nil)

(defmacro with-trace-id
  "Binds a trace ID for the scope of body.

   If no trace-id provided, generates a new one."
  [trace-id & body]
  `(binding [*trace-id* (or ~trace-id (generate-trace-id))]
     ~@body))

(defn get-trace-id
  "Returns the current trace ID, or generates one if not set."
  []
  (or *trace-id* (generate-trace-id)))
