# ArcNet Deployment Guide

## Overview

This guide covers deploying ArcNet in production environments, including infrastructure setup, configuration, monitoring, and operational best practices.

## Deployment Architectures

### Single Geozone (Development/Testing)

Minimal deployment for development or testing:

```
┌─────────────────────────────────┐
│   Regional Aggregator (CAISO)  │
│   - XTDB embedded               │
│   - Kafka consumer              │
│   - WebSocket server            │
│   - Prometheus metrics          │
└────────────▲────────────────────┘
             │
    ┌────────┴────────┐
    │                 │
┌───▼────┐      ┌────▼────┐
│ Kafka  │      │ Console │
│ Broker │      │ (Static)│
└────────┘      └─────────┘
```

**Components**:
- 1 Kafka broker
- 1 Regional aggregator
- 1 Console instance (static hosting)
- Optional: Prometheus + Grafana

**Suitable for**:
- Development environments
- Testing and CI/CD
- Small-scale deployments (< 50 nodes)

### Multi-Geozone (Production)

Production deployment with multiple geozones:

```
┌─────────────────────────────────────┐
│         ORNL "Brain"                │
│   - XTDB distributed                │
│   - Global coordination             │
│   - HPC integration                 │
└──────────────▲──────────────────────┘
               │
 ┌─────────────┼─────────────────────┐
 │             │                     │
 ▼             ▼                     ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Aggregator│ │Aggregator│ │Aggregator│
│CAISO     │ │ERCOT     │ │PJM       │
└────▲─────┘ └────▲─────┘ └────▲─────┘
     │            │            │
┌────┴────┐  ┌───┴────┐  ┌───┴────┐
│ Kafka   │  │ Kafka  │  │ Kafka  │
│ Cluster │  │ Cluster│  │ Cluster│
└─────────┘  └────────┘  └─────────┘
```

**Components**:
- 3+ Kafka clusters (one per geozone)
- 3+ Regional aggregators (one per geozone)
- 1 Central orchestrator (ORNL)
- 1+ Console instances (CDN-distributed)
- Prometheus + Grafana + Jaeger

**Suitable for**:
- Production environments
- Large-scale deployments (100+ nodes)
- Multi-region operations

## Infrastructure Requirements

### Regional Aggregator

**Compute**:
- 4 vCPUs
- 8 GB RAM
- 50 GB SSD (for XTDB data)

**Network**:
- 1 Gbps network interface
- Low latency to Kafka cluster (< 10ms)
- Public IP for WebSocket connections

**Software**:
- Java 11+ (OpenJDK recommended)
- Clojure CLI 1.11+
- Docker (optional, for containerized deployment)

### Kafka Cluster

**Brokers** (minimum 3 for production):
- 4 vCPUs per broker
- 16 GB RAM per broker
- 500 GB SSD per broker (for message retention)

**Zookeeper** (minimum 3 for production):
- 2 vCPUs per instance
- 4 GB RAM per instance
- 50 GB SSD per instance

**Network**:
- 10 Gbps network interface
- Low latency between brokers (< 5ms)

### Console (Static Hosting)

**Options**:
- CDN (Cloudflare, AWS CloudFront, Fastly)
- Static hosting (Netlify, Vercel, AWS S3 + CloudFront)
- Nginx/Apache on VM

**Requirements**:
- HTTPS support
- WebSocket proxy support
- Gzip compression

## Deployment Steps

### Step 1: Deploy Kafka Cluster

#### Using Docker Compose (Development)

```yaml
# docker-compose.yml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    volumes:
      - zookeeper-data:/var/lib/zookeeper/data
      - zookeeper-logs:/var/lib/zookeeper/log

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    volumes:
      - kafka-data:/var/lib/kafka/data

volumes:
  zookeeper-data:
  zookeeper-logs:
  kafka-data:
```

```bash
docker-compose up -d
```

#### Using Kubernetes (Production)

```yaml
# kafka-cluster.yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: arcnet-kafka
spec:
  kafka:
    version: 3.6.0
    replicas: 3
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
      - name: tls
        port: 9093
        type: internal
        tls: true
    config:
      offsets.topic.replication.factor: 3
      transaction.state.log.replication.factor: 3
      transaction.state.log.min.isr: 2
      default.replication.factor: 3
      min.insync.replicas: 2
    storage:
      type: persistent-claim
      size: 500Gi
      class: fast-ssd
  zookeeper:
    replicas: 3
    storage:
      type: persistent-claim
      size: 50Gi
      class: fast-ssd
```

```bash
kubectl apply -f kafka-cluster.yaml
```

### Step 2: Deploy Regional Aggregator

#### Using Docker

```dockerfile
# Dockerfile
FROM clojure:temurin-21-tools-deps-alpine
WORKDIR /app
COPY deps.edn .
RUN clojure -P
COPY . .
EXPOSE 8080 9090
CMD ["clojure", "-M:run", "aggregator"]
```

```bash
# Build image
docker build -t arcnet/aggregator:latest .

# Run container
docker run -d \
  --name arcnet-aggregator-caiso \
  -p 8080:8080 \
  -p 9090:9090 \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e GEOZONE_ID=CAISO \
  -e XTDB_DATA_DIR=/data/xtdb \
  -v /var/lib/arcnet/xtdb:/data/xtdb \
  arcnet/aggregator:latest
```

#### Using Kubernetes

```yaml
# aggregator-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: arcnet-aggregator-caiso
  labels:
    app: arcnet-aggregator
    geozone: caiso
spec:
  replicas: 2
  selector:
    matchLabels:
      app: arcnet-aggregator
      geozone: caiso
  template:
    metadata:
      labels:
        app: arcnet-aggregator
        geozone: caiso
    spec:
      containers:
      - name: aggregator
        image: arcnet/aggregator:latest
        args: ["aggregator", "--geozone", "CAISO"]
        env:
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "arcnet-kafka-bootstrap:9092"
        - name: XTDB_DATA_DIR
          value: "/data/xtdb"
        - name: PROMETHEUS_PORT
          value: "9090"
        ports:
        - containerPort: 8080
          name: websocket
        - containerPort: 9090
          name: metrics
        volumeMounts:
        - name: xtdb-data
          mountPath: /data/xtdb
        resources:
          requests:
            memory: "4Gi"
            cpu: "2"
          limits:
            memory: "8Gi"
            cpu: "4"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
      volumes:
      - name: xtdb-data
        persistentVolumeClaim:
          claimName: xtdb-pvc-caiso
---
apiVersion: v1
kind: Service
metadata:
  name: arcnet-aggregator-caiso
spec:
  selector:
    app: arcnet-aggregator
    geozone: caiso
  ports:
  - name: websocket
    port: 8080
    targetPort: 8080
  - name: metrics
    port: 9090
    targetPort: 9090
  type: LoadBalancer
```

```bash
kubectl apply -f aggregator-deployment.yaml
```

### Step 3: Deploy Console

#### Build for Production

```bash
cd arcnet-console

# Set production WebSocket URL
echo "VITE_WS_URL=wss://telemetry.arcnet.io" > .env

# Build
npm run build

# Output is in dist/
```

#### Deploy to Netlify

```bash
# Install Netlify CLI
npm install -g netlify-cli

# Deploy
netlify deploy --prod --dir=dist
```

#### Deploy to AWS S3 + CloudFront

```bash
# Sync to S3
aws s3 sync dist/ s3://arcnet-console-bucket --delete

# Invalidate CloudFront cache
aws cloudfront create-invalidation \
  --distribution-id E1234567890ABC \
  --paths "/*"
```

#### Deploy with Nginx

```nginx
# /etc/nginx/sites-available/arcnet-console
server {
    listen 80;
    server_name console.arcnet.io;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name console.arcnet.io;

    ssl_certificate /etc/letsencrypt/live/console.arcnet.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/console.arcnet.io/privkey.pem;

    root /var/www/arcnet-console;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;

    # SPA routing
    location / {
        try_files $uri $uri/ /index.html;
    }

    # WebSocket proxy
    location /telemetry {
        proxy_pass http://aggregator-backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Cache static assets
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

```bash
# Enable site
ln -s /etc/nginx/sites-available/arcnet-console /etc/nginx/sites-enabled/
nginx -t
systemctl reload nginx
```

## Configuration

### Environment Variables

Create `.env` file or set environment variables:

```bash
# Kafka Configuration
KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092,kafka3:9092
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_MECHANISM=PLAIN
KAFKA_SASL_USERNAME=arcnet
KAFKA_SASL_PASSWORD=<secret>

# XTDB Configuration
XTDB_DATA_DIR=/var/lib/arcnet/xtdb
GEOZONE_ID=CAISO

# Observability
PROMETHEUS_PORT=9090
OTEL_EXPORTER_ENDPOINT=http://jaeger:4317
LOG_LEVEL=info

# Security
TLS_CERT_PATH=/etc/arcnet/certs/server.crt
TLS_KEY_PATH=/etc/arcnet/certs/server.key
```

## Next Steps

- **[Monitoring Setup](operations/monitoring.md)** - Configure Prometheus and Grafana
- **[Security Configuration](operations/security.md)** - Set up authentication and TLS
- **[Troubleshooting](troubleshooting.md)** - Common deployment issues

