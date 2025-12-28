┌─────────────────────────────────────┐
                        │         ORNL "Brain"                │
                        │   (Frontier/Lux + XTDB Tier-2)      │
                        └──────────────▲──────────────────────┘
                                       │ ornl.bridge.ingress
                                       │ (mTLS + Globus)
         ┌─────────────────────────────┼─────────────────────────────┐
         │                             │                             │
         ▼                             ▼                             ▼
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  Geozone-West   │          │  Geozone-Central│          │  Geozone-East   │
│  Aggregator     │◄────────►│  Aggregator     │◄────────►│  Aggregator     │
│  (XTDB + Kafka) │          │  (XTDB + Kafka) │          │  (XTDB + Kafka) │
└────────▲────────┘          └────────▲────────┘          └────────▲────────┘
         │                            │                            │
    ┌────┴────┐                  ┌────┴────┐                  ┌────┴────┐
    │Ganglions│                  │Ganglions│                  │Ganglions│
    │(~50 1MW │                  │(~50 1MW │                  │(~50 1MW │
    │ nodes)  │                  │ nodes)  │                  │ nodes)  │
    └─────────┘                  └─────────┘                  └─────────┘