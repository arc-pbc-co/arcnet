# ISO/RTO Regions for Node Geographic Distribution

## Overview

The mock node generator now uses **real geographic locations** based on the 7 major ISO/RTO (Independent System Operator / Regional Transmission Organization) regions in the United States. This provides realistic clustering of compute nodes that aligns with actual energy grid infrastructure.

## The 7 ISO/RTO Regions

Based on: [FERC ISO/RTO Map](https://electricenergyonline.com/energy/magazine/1149/article/the-bigger-picture-ferc-requires-greater-transparency-regarding-rto-iso-uplift.htm)

### 1. **CAISO** - California ISO (18% of nodes)
**Coverage:** California  
**Major Centers:**
- San Jose (Silicon Valley): -121.9°, 37.3°
- Los Angeles: -118.2°, 34.0°
- San Francisco Bay Area: -122.4°, 37.8°
- San Diego: -117.2°, 32.7°

### 2. **ERCOT** - Electric Reliability Council of Texas (16% of nodes)
**Coverage:** Most of Texas  
**Major Centers:**
- Austin: -97.7°, 30.3°
- Houston: -95.4°, 29.8°
- Dallas: -96.8°, 32.8°
- San Antonio: -98.5°, 29.4°

### 3. **SPP** - Southwest Power Pool (12% of nodes)
**Coverage:** Kansas, Oklahoma, parts of surrounding states  
**Major Centers:**
- Oklahoma City: -97.5°, 35.5°
- Kansas City: -94.6°, 39.1°
- Lincoln, NE: -96.7°, 40.8°
- Wichita, KS: -97.3°, 37.7°

### 4. **MISO** - Midcontinent ISO (20% of nodes)
**Coverage:** Midwest and Great Lakes region  
**Major Centers:**
- Chicago: -87.6°, 41.9°
- Indianapolis: -86.2°, 39.8°
- Minneapolis: -93.3°, 45.0°
- St. Louis: -90.2°, 38.6°
- Detroit: -83.0°, 42.3°

### 5. **PJM** - PJM Interconnection (22% of nodes - largest)
**Coverage:** Mid-Atlantic states  
**Major Centers:**
- Washington DC/Virginia: -77.0°, 38.9°
- Philadelphia: -75.2°, 40.0°
- Baltimore: -76.6°, 39.3°
- Pittsburgh: -80.0°, 40.4°
- Columbus, OH: -82.0°, 39.9°

### 6. **NYISO** - New York ISO (10% of nodes)
**Coverage:** New York State  
**Major Centers:**
- New York City: -74.0°, 40.7°
- Albany: -73.8°, 42.7°
- Rochester: -77.6°, 43.2°
- Syracuse: -76.1°, 43.0°

### 7. **ISO-NE** - ISO New England (10% of nodes)
**Coverage:** New England states (CT, MA, ME, NH, RI, VT)  
**Major Centers:**
- Boston: -71.1°, 42.4°
- Hartford, CT: -72.7°, 41.8°
- Providence, RI: -71.4°, 41.8°
- Burlington, VT: -72.6°, 44.3°

## Node Distribution Algorithm

### Region Selection
Nodes are distributed across the 7 regions using weighted random selection:
- **PJM**: 22% (largest RTO by load)
- **MISO**: 20% (second largest)
- **CAISO**: 18% (California's large tech presence)
- **ERCOT**: 16% (Texas's independent grid)
- **SPP**: 12% (smaller regional coverage)
- **NYISO**: 10% (concentrated in NY state)
- **ISO-NE**: 10% (smaller New England states)

### Center Selection
Within each region, nodes are randomly assigned to one of the major city centers.

### Geographic Spread
Each node is positioned with a **1.5-degree spread** around its assigned center:
- Provides realistic clustering around data center locations
- Tighter than previous 3-degree spread for more accurate positioning
- Ensures nodes stay within their respective states/regions

### Node Naming Convention
Nodes are named using the pattern: `{ISO}-{City}-{Index}`

Examples:
- `CAISO-SanJose-001`
- `ERCOT-Austin-042`
- `PJM-WashingtonDC-073`
- `MISO-Chicago-099`

## Benefits

1. **Geographic Accuracy**: Nodes now appear in correct US locations
2. **Energy Grid Alignment**: Clustering matches real energy infrastructure
3. **Realistic Distribution**: Weighted by actual grid size and importance
4. **Easy Identification**: Node names clearly indicate their ISO region and city
5. **Visual Clarity**: Nodes properly overlay on US state boundaries

## Visualization

With the country and state boundary layers enabled, you can now:
- See nodes correctly positioned within US states
- Identify regional clustering patterns
- Understand the geographic distribution of the ArcNet network
- Correlate node locations with energy grid infrastructure

## Technical Implementation

**File:** `arcnet-console/src/components/Globe/mockNodes.ts`

**Key Changes:**
- Replaced generic `DATA_CENTER_REGIONS` with `ISO_RTO_REGIONS`
- Added multiple centers per region for better distribution
- Reduced spread from 3° to 1.5° for tighter clustering
- Updated node naming to include ISO region and city
- Maintained all other node attributes (GPU, energy, status, etc.)

