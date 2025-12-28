# ArcNet Troubleshooting Guide

## Console Issues

### Console shows "DISCONNECTED"

**Symptoms**:
- Connection status indicator shows red "DISCONNECTED"
- No nodes appear on the globe
- Event log is empty

**Possible Causes**:

1. **Aggregator not running**
   ```bash
   # Check if aggregator is running
   curl http://localhost:8080/health
   
   # Expected response: {"status": "ok"}
   ```

2. **Incorrect WebSocket URL**
   ```bash
   # Check .env file
   cat arcnet-console/.env
   
   # Should contain:
   # VITE_WS_URL=ws://localhost:8080/telemetry
   ```

3. **Firewall blocking WebSocket**
   ```bash
   # Test WebSocket connection
   wscat -c ws://localhost:8080/telemetry
   
   # Should connect successfully
   ```

4. **CORS issues** (if console and aggregator on different domains)
   - Check aggregator logs for CORS errors
   - Ensure aggregator allows console origin

**Solutions**:

```bash
# 1. Verify aggregator is running
cd arcnet-protocol
clojure -M:run aggregator --geozone CAISO

# 2. Check WebSocket URL in console
cd arcnet-console
echo "VITE_WS_URL=ws://localhost:8080/telemetry" > .env
npm run dev

# 3. Check browser console for errors
# Open DevTools → Console tab
# Look for WebSocket connection errors
```

### No nodes appearing on globe

**Symptoms**:
- Console shows "CONNECTED"
- Globe is visible but empty
- No telemetry updates in event log

**Possible Causes**:

1. **No nodes publishing telemetry**
   ```bash
   # Check Kafka topic for messages
   docker exec -it kafka kafka-console-consumer \
     --topic arc.telemetry.node \
     --bootstrap-server localhost:9092 \
     --from-beginning
   ```

2. **XTDB not receiving updates**
   ```bash
   # Check aggregator logs
   docker logs arcnet-aggregator-caiso
   
   # Look for XTDB write errors
   ```

3. **Geozone mismatch**
   - Nodes publishing to different geozone than aggregator is monitoring

**Solutions**:

```bash
# 1. Start simulator to generate telemetry
cd arcnet-protocol
clojure -M:run simulator --nodes 20 --geozone CAISO

# 2. Verify telemetry is being published
docker exec -it kafka kafka-console-consumer \
  --topic arc.telemetry.node \
  --bootstrap-server localhost:9092 \
  --max-messages 5

# 3. Check aggregator is consuming messages
# Look for log messages like:
# "Received telemetry from node-001"
```

### Console performance issues

**Symptoms**:
- Low frame rate (< 30 FPS)
- Laggy globe interaction
- High memory usage

**Possible Causes**:

1. **Too many nodes** (> 1000)
2. **Too many inference arcs** (> 100 active)
3. **Browser hardware acceleration disabled**
4. **Insufficient GPU memory**

**Solutions**:

```javascript
// 1. Reduce node count in mock mode
// Edit arcnet-console/src/hooks/useMockTelemetry.ts
const MOCK_NODE_COUNT = 20; // Reduce from 50

// 2. Limit active arcs
// Edit arcnet-console/src/stores/arcnetStore.ts
const MAX_ACTIVE_ARCS = 50; // Add limit

// 3. Enable hardware acceleration
// Chrome: chrome://settings → System → Use hardware acceleration

// 4. Check WebGL support
// Visit: https://get.webgl.org/
```

## Backend Issues

### Kafka connection refused

**Symptoms**:
- Aggregator fails to start
- Error: "Connection refused: localhost:9092"

**Solutions**:

```bash
# 1. Verify Kafka is running
docker-compose ps

# Expected output:
# NAME      STATUS    PORTS
# kafka     running   0.0.0.0:9092->9092/tcp
# zookeeper running   0.0.0.0:2181->2181/tcp

# 2. Check Kafka logs
docker-compose logs kafka

# 3. Restart Kafka
docker-compose restart kafka

# 4. Verify port is not in use
lsof -i :9092

# 5. Test connection
telnet localhost 9092
```

### XTDB write errors

**Symptoms**:
- Aggregator logs show XTDB errors
- Nodes not appearing in queries
- Error: "Failed to write to XTDB"

**Solutions**:

```bash
# 1. Check XTDB data directory permissions
ls -la /tmp/arcnet-xtdb
# Should be writable by current user

# 2. Clear XTDB data (WARNING: deletes all state)
rm -rf /tmp/arcnet-xtdb/*

# 3. Restart aggregator
clojure -M:run aggregator --geozone CAISO

# 4. Verify XTDB is accepting writes
# In REPL:
(require '[arcnet.state.regional :as regional])
(regional/start-xtdb! {:geozone-id "CAISO"})
(regional/query-all-nodes)
```

### High Kafka consumer lag

**Symptoms**:
- Telemetry updates delayed (> 30s)
- Kafka consumer lag increasing
- Aggregator CPU at 100%

**Solutions**:

```bash
# 1. Check consumer lag
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group arcnet-aggregator-caiso

# 2. Increase consumer threads
# Edit config.edn:
{:kafka
 {:consumer-threads 4}} ; Increase from 1

# 3. Increase poll interval
{:kafka
 {:max-poll-interval-ms 300000}} ; 5 minutes

# 4. Scale aggregator horizontally
# Deploy multiple aggregator instances with same consumer group
```

### Scheduler not dispatching requests

**Symptoms**:
- Inference requests published but not dispatched
- No arcs appearing on console
- Requests timing out

**Solutions**:

```bash
# 1. Check scheduler is running
ps aux | grep scheduler

# 2. Verify nodes are online in XTDB
# In REPL:
(require '[arcnet.state.regional :as regional])
(regional/query-nodes-by-status :online)

# 3. Check node has required model loaded
(regional/query-nodes-with-model "llama-3-70b")

# 4. Verify GPU utilization is below threshold
(regional/query-nodes-by-gpu-utilization 0.0 0.85)

# 5. Check scheduler logs for errors
tail -f /var/log/arcnet/scheduler.log
```

## Network Issues

### High inference latency

**Symptoms**:
- P99 latency > 1000ms
- Requests timing out
- Poor user experience

**Possible Causes**:

1. **Cross-geozone routing**
   - Requests routed to distant nodes
   - Network latency between geozones

2. **Node overload**
   - GPU utilization > 90%
   - Too many concurrent requests

3. **Model not loaded**
   - Cold start penalty
   - Model loading time

**Solutions**:

```bash
# 1. Check geozone distribution
# Ensure nodes are in correct geozones

# 2. Adjust scheduling weights
# Edit arcnet/scheduler/scoring.clj:
(defn score-candidate [node request]
  (+ (* 200 (if (= (:geozone node) (:requester-geozone request)) 1 0)) ; Increase weight
     (* 50 (if (= (:energy-source node) :cogen) 1 0))
     (* 30 (- 1.0 (:gpu-utilization node)))
     (* 20 (:battery-level node))))

# 3. Pre-load models on nodes
# Ensure popular models are loaded on all nodes

# 4. Monitor Prometheus metrics
# Query: histogram_quantile(0.99, rate(arcnet_inference_latency_ms_bucket[5m]))
```

### Nodes going stale

**Symptoms**:
- Nodes showing orange "stale" status
- Telemetry not updating
- Nodes dropping offline

**Solutions**:

```bash
# 1. Check node is publishing telemetry
# On node:
tail -f /var/log/arcnet/node.log

# 2. Verify Kafka connectivity from node
telnet kafka-broker 9092

# 3. Check network latency
ping kafka-broker

# 4. Increase telemetry timeout
# Edit aggregator config:
{:telemetry
 {:stale-threshold-ms 60000}} ; Increase from 30000

# 5. Check for network partitions
# Verify node can reach Kafka broker
traceroute kafka-broker
```

## HPC Integration Issues

### Globus transfer failing

**Symptoms**:
- HPC jobs stuck in "pending" status
- Transfer progress at 0%
- Error: "Globus transfer failed"

**Solutions**:

```bash
# 1. Verify Globus endpoint is active
globus endpoint show ornl-frontier

# 2. Check authentication
globus login

# 3. Test transfer manually
globus transfer \
  source-endpoint:/path/to/dataset \
  ornl-frontier:/path/to/destination

# 4. Check bridge orchestrator logs
tail -f /var/log/arcnet/bridge.log

# 5. Verify mTLS certificates
openssl s_client -connect ornl-bridge:443 -cert client.crt -key client.key
```

### Jobs not being classified

**Symptoms**:
- Training jobs submitted but not routed
- Jobs stuck in submission queue
- No HPC or federated tasks created

**Solutions**:

```bash
# 1. Check bridge orchestrator is running
ps aux | grep bridge

# 2. Verify job submission topic
docker exec -it kafka kafka-console-consumer \
  --topic arc.job.submission \
  --bootstrap-server localhost:9092

# 3. Check classifier logic
# In REPL:
(require '[arcnet.bridge.classifier :as classifier])
(classifier/classify-job
  {:estimated-flops 1e19
   :dataset-size-gb 150
   :urgency :normal})
; Should return :hpc

# 4. Check bridge logs for errors
grep ERROR /var/log/arcnet/bridge.log
```

## Performance Optimization

### Reduce memory usage

```bash
# 1. Limit XTDB cache size
{:xtdb
 {:cache-size-mb 512}} ; Reduce from 1024

# 2. Reduce Kafka consumer buffer
{:kafka
 {:fetch-max-bytes 1048576}} ; 1MB

# 3. Limit event log size in console
# Edit arcnet-console/src/stores/arcnetStore.ts
const MAX_EVENTS = 500; // Reduce from 1000
```

### Improve throughput

```bash
# 1. Increase Kafka partitions
docker exec -it kafka kafka-topics \
  --alter \
  --topic arc.telemetry.node \
  --partitions 10 \
  --bootstrap-server localhost:9092

# 2. Increase consumer threads
{:kafka
 {:consumer-threads 8}}

# 3. Batch XTDB writes
{:xtdb
 {:batch-size 100
  :batch-timeout-ms 1000}}
```

## Getting Help

If you're still experiencing issues:

1. **Check logs**: Enable debug logging with `LOG_LEVEL=debug`
2. **Search issues**: [GitHub Issues](https://github.com/arc-pbc-co/arcnet/issues)
3. **Open an issue**: Include logs, configuration, and steps to reproduce
4. **Review documentation**: [Architecture](architecture.md), [API Reference](api/index.md)

## Common Error Messages

| Error | Cause | Solution |
|-------|-------|----------|
| `Connection refused: localhost:9092` | Kafka not running | Start Kafka with `docker-compose up -d` |
| `Failed to write to XTDB` | Permission or disk space issue | Check permissions and disk space |
| `WebSocket connection failed` | Aggregator not running or firewall | Start aggregator and check firewall |
| `Schema validation failed` | Invalid message format | Check schema version and message structure |
| `No candidates found for request` | No nodes available | Start simulator or check node status |
| `Globus transfer timeout` | Network or authentication issue | Check Globus endpoint and credentials |

## Next Steps

- **[Deployment Guide](deployment.md)** - Production deployment
- **[Operations Guide](operations/monitoring.md)** - Monitoring and alerting
- **[Development Guide](development.md)** - Contributing to ArcNet

