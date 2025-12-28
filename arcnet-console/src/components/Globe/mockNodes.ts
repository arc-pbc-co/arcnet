/**
 * Mock node data generator for testing
 *
 * Generates nodes at major US freight airport locations with realistic attributes.
 * Airport data sourced from Transport Topics 2025 Global Freight Rankings.
 */

import type { Node, EnergySource, NodeStatus } from '@/types/arcnet';

/**
 * US Freight Airport locations with coordinates and RTO/ISO regions
 * Excludes Mexico locations per requirements
 */
const FREIGHT_AIRPORTS = [
  // Top 50 US Freight Airports by 2024 landed weight
  { code: 'ANC', name: 'Ted Stevens Anchorage Intl', city: 'Anchorage', state: 'AK', lng: -149.9961, lat: 61.1743, iso: 'AECI' }, // Alaska not in an ISO, using adjacent
  { code: 'MEM', name: 'Memphis Intl', city: 'Memphis', state: 'TN', lng: -89.9767, lat: 35.0421, iso: 'MISO' },
  { code: 'SDF', name: 'Louisville Muhammad Ali Intl', city: 'Louisville', state: 'KY', lng: -85.7364, lat: 38.1744, iso: 'PJM' },
  { code: 'MIA', name: 'Miami Intl', city: 'Miami', state: 'FL', lng: -80.2906, lat: 25.7959, iso: 'FRCC' }, // Florida Reliability Coordinating Council
  { code: 'CVG', name: 'Cincinnati/N Kentucky Intl', city: 'Hebron', state: 'KY', lng: -84.6678, lat: 39.0488, iso: 'PJM' },
  { code: 'LAX', name: 'Los Angeles Intl', city: 'Los Angeles', state: 'CA', lng: -118.4085, lat: 33.9416, iso: 'CAISO' },
  { code: 'ORD', name: 'Chicago OHare Intl', city: 'Chicago', state: 'IL', lng: -87.9073, lat: 41.9742, iso: 'PJM' },
  { code: 'ONT', name: 'Ontario Intl', city: 'Ontario', state: 'CA', lng: -117.6012, lat: 34.0560, iso: 'CAISO' },
  { code: 'JFK', name: 'John F Kennedy Intl', city: 'New York', state: 'NY', lng: -73.7781, lat: 40.6413, iso: 'NYISO' },
  { code: 'IND', name: 'Indianapolis Intl', city: 'Indianapolis', state: 'IN', lng: -86.2944, lat: 39.7173, iso: 'MISO' },
  { code: 'DFW', name: 'Dallas Fort Worth Intl', city: 'Dallas', state: 'TX', lng: -97.0403, lat: 32.8998, iso: 'ERCOT' },
  { code: 'HNL', name: 'Daniel K Inouye Intl', city: 'Honolulu', state: 'HI', lng: -157.9225, lat: 21.3187, iso: 'HECO' }, // Hawaiian Electric
  { code: 'OAK', name: 'Oakland Intl', city: 'Oakland', state: 'CA', lng: -122.2208, lat: 37.7126, iso: 'CAISO' },
  { code: 'RFD', name: 'Chicago Rockford Intl', city: 'Rockford', state: 'IL', lng: -89.0972, lat: 42.1954, iso: 'MISO' },
  { code: 'ATL', name: 'Hartsfield-Jackson Atlanta Intl', city: 'Atlanta', state: 'GA', lng: -84.4281, lat: 33.6407, iso: 'SERC' }, // SERC Reliability Corporation
  { code: 'SEA', name: 'Seattle-Tacoma Intl', city: 'Seattle', state: 'WA', lng: -122.3088, lat: 47.4502, iso: 'CAISO' }, // Western grid
  { code: 'EWR', name: 'Newark Liberty Intl', city: 'Newark', state: 'NJ', lng: -74.1687, lat: 40.6895, iso: 'PJM' },
  { code: 'PHL', name: 'Philadelphia Intl', city: 'Philadelphia', state: 'PA', lng: -75.2411, lat: 39.8744, iso: 'PJM' },
  { code: 'IAH', name: 'George Bush Intercontinental', city: 'Houston', state: 'TX', lng: -95.3414, lat: 29.9902, iso: 'ERCOT' },
  { code: 'PHX', name: 'Phoenix Sky Harbor Intl', city: 'Phoenix', state: 'AZ', lng: -112.0116, lat: 33.4373, iso: 'WECC' }, // Western Electricity
  { code: 'PDX', name: 'Portland Intl', city: 'Portland', state: 'OR', lng: -122.5975, lat: 45.5898, iso: 'WECC' },
  { code: 'AFW', name: 'Perot Field Fort Worth Alliance', city: 'Fort Worth', state: 'TX', lng: -97.3189, lat: 32.9876, iso: 'ERCOT' },
  { code: 'DEN', name: 'Denver Intl', city: 'Denver', state: 'CO', lng: -104.6737, lat: 39.8561, iso: 'WECC' },
  { code: 'SBD', name: 'San Bernardino Intl', city: 'San Bernardino', state: 'CA', lng: -117.2354, lat: 34.0954, iso: 'CAISO' },
  { code: 'BOS', name: 'Boston Logan Intl', city: 'Boston', state: 'MA', lng: -71.0096, lat: 42.3656, iso: 'ISO-NE' },
  { code: 'DTW', name: 'Detroit Metro Wayne County', city: 'Detroit', state: 'MI', lng: -83.3534, lat: 42.2124, iso: 'MISO' },
  { code: 'MSP', name: 'Minneapolis-St Paul Intl', city: 'Minneapolis', state: 'MN', lng: -93.2218, lat: 44.8820, iso: 'MISO' },
  { code: 'SFO', name: 'San Francisco Intl', city: 'San Francisco', state: 'CA', lng: -122.3789, lat: 37.6213, iso: 'CAISO' },
  { code: 'CLT', name: 'Charlotte Douglas Intl', city: 'Charlotte', state: 'NC', lng: -80.9431, lat: 35.2140, iso: 'SERC' },
  { code: 'BWI', name: 'Baltimore/Washington Intl', city: 'Baltimore', state: 'MD', lng: -76.6684, lat: 39.1754, iso: 'PJM' },
  { code: 'SAN', name: 'San Diego Intl', city: 'San Diego', state: 'CA', lng: -117.1933, lat: 32.7338, iso: 'CAISO' },
  { code: 'TPA', name: 'Tampa Intl', city: 'Tampa', state: 'FL', lng: -82.5332, lat: 27.9756, iso: 'FRCC' },
  { code: 'MCO', name: 'Orlando Intl', city: 'Orlando', state: 'FL', lng: -81.3089, lat: 28.4312, iso: 'FRCC' },
  { code: 'LAS', name: 'Harry Reid Intl', city: 'Las Vegas', state: 'NV', lng: -115.1523, lat: 36.0840, iso: 'WECC' },
  { code: 'SAT', name: 'San Antonio Intl', city: 'San Antonio', state: 'TX', lng: -98.4698, lat: 29.5337, iso: 'ERCOT' },
  { code: 'AUS', name: 'Austin-Bergstrom Intl', city: 'Austin', state: 'TX', lng: -97.6699, lat: 30.1975, iso: 'ERCOT' },
  { code: 'STL', name: 'St Louis Lambert Intl', city: 'St Louis', state: 'MO', lng: -90.3700, lat: 38.7487, iso: 'MISO' },
  { code: 'BNA', name: 'Nashville Intl', city: 'Nashville', state: 'TN', lng: -86.6782, lat: 36.1263, iso: 'TVA' }, // Tennessee Valley Authority
  { code: 'MCI', name: 'Kansas City Intl', city: 'Kansas City', state: 'MO', lng: -94.7139, lat: 39.2976, iso: 'SPP' },
  { code: 'RDU', name: 'Raleigh-Durham Intl', city: 'Raleigh', state: 'NC', lng: -78.7880, lat: 35.8776, iso: 'SERC' },
  { code: 'SLC', name: 'Salt Lake City Intl', city: 'Salt Lake City', state: 'UT', lng: -111.9791, lat: 40.7899, iso: 'WECC' },
  { code: 'PIT', name: 'Pittsburgh Intl', city: 'Pittsburgh', state: 'PA', lng: -80.2329, lat: 40.4915, iso: 'PJM' },
  { code: 'CMH', name: 'John Glenn Columbus Intl', city: 'Columbus', state: 'OH', lng: -82.8919, lat: 39.9980, iso: 'PJM' },
  { code: 'CLE', name: 'Cleveland Hopkins Intl', city: 'Cleveland', state: 'OH', lng: -81.8498, lat: 41.4117, iso: 'PJM' },
  { code: 'RSW', name: 'Southwest Florida Intl', city: 'Fort Myers', state: 'FL', lng: -81.7552, lat: 26.5362, iso: 'FRCC' },
  { code: 'SMF', name: 'Sacramento Intl', city: 'Sacramento', state: 'CA', lng: -121.5908, lat: 38.6954, iso: 'CAISO' },
  { code: 'JAX', name: 'Jacksonville Intl', city: 'Jacksonville', state: 'FL', lng: -81.6879, lat: 30.4941, iso: 'FRCC' },
  { code: 'OMA', name: 'Eppley Airfield', city: 'Omaha', state: 'NE', lng: -95.8941, lat: 41.3032, iso: 'SPP' },
  { code: 'ABQ', name: 'Albuquerque Intl Sunport', city: 'Albuquerque', state: 'NM', lng: -106.6094, lat: 35.0402, iso: 'WECC' },
  { code: 'BDL', name: 'Bradley Intl', city: 'Hartford', state: 'CT', lng: -72.6832, lat: 41.9389, iso: 'ISO-NE' },
];

// Model IDs for random assignment
const MODEL_IDS = [
  'llama-3.1-8b',
  'llama-3.1-70b',
  'mistral-7b',
  'mixtral-8x7b',
  'codellama-34b',
  'deepseek-coder-33b',
  'qwen-2.5-72b',
];

/**
 * Seeded random number generator for reproducible results
 */
function seededRandom(seed: number): () => number {
  return () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    return seed / 0x7fffffff;
  };
}

/**
 * Generate a geohash-like string from coordinates
 */
function generateGeohash(lng: number, lat: number): string {
  const chars = '0123456789bcdefghjkmnpqrstuvwxyz';
  let hash = '';
  let minLat = -90, maxLat = 90;
  let minLng = -180, maxLng = 180;

  for (let i = 0; i < 6; i++) {
    // Longitude bit
    const midLng = (minLng + maxLng) / 2;
    if (lng >= midLng) {
      minLng = midLng;
      hash += chars[Math.floor(Math.random() * 16) + 16];
    } else {
      maxLng = midLng;
      hash += chars[Math.floor(Math.random() * 16)];
    }

    // Latitude bit
    const midLat = (minLat + maxLat) / 2;
    if (lat >= midLat) {
      minLat = midLat;
    } else {
      maxLat = midLat;
    }
  }

  return hash.slice(0, 6);
}

/**
 * Generate a single mock node at a freight airport location
 */
function generateNode(index: number, random: () => number): Node {
  // Select airport based on index (cycle through if more nodes than airports)
  const airport = FREIGHT_AIRPORTS[index % FREIGHT_AIRPORTS.length];

  // Add small random offset to prevent exact overlap (within ~5km)
  const lngOffset = (random() - 0.5) * 0.05;
  const latOffset = (random() - 0.5) * 0.05;
  const lng = airport.lng + lngOffset;
  const lat = airport.lat + latOffset;

  // Energy source distribution: 60% cogen, 25% grid, 15% battery
  let energySource: EnergySource;
  const energyRoll = random();
  if (energyRoll < 0.6) {
    energySource = 'cogen';
  } else if (energyRoll < 0.85) {
    energySource = 'grid';
  } else {
    energySource = 'battery';
  }

  // Status distribution: 70% online, 15% busy, 10% idle, 5% stale
  let status: NodeStatus;
  const statusRoll = random();
  if (statusRoll < 0.7) {
    status = 'online';
  } else if (statusRoll < 0.85) {
    status = 'busy';
  } else if (statusRoll < 0.95) {
    status = 'idle';
  } else {
    status = 'stale';
  }

  // GPU count: 1-8, weighted towards lower counts
  const gpuCount = Math.ceil(Math.pow(random(), 0.5) * 8);

  // GPU utilization: varies by status
  let gpuUtilization: number;
  if (status === 'busy') {
    gpuUtilization = 0.7 + random() * 0.3; // 70-100%
  } else if (status === 'online') {
    gpuUtilization = 0.2 + random() * 0.5; // 20-70%
  } else if (status === 'idle') {
    gpuUtilization = random() * 0.2; // 0-20%
  } else {
    gpuUtilization = random() * 0.5; // stale - last known value
  }

  // Battery level: varies by energy source
  let batteryLevel: number;
  if (energySource === 'cogen') {
    batteryLevel = 0.5 + random() * 0.5; // 50-100% (cogen tends to have good battery)
  } else if (energySource === 'battery') {
    batteryLevel = 0.2 + random() * 0.6; // 20-80% (actively using battery)
  } else {
    batteryLevel = 0.8 + random() * 0.2; // 80-100% (grid keeps battery topped up)
  }

  // Models loaded: 1-3 random models
  const modelCount = 1 + Math.floor(random() * 3);
  const modelsLoaded: string[] = [];
  const shuffledModels = [...MODEL_IDS].sort(() => random() - 0.5);
  for (let i = 0; i < modelCount; i++) {
    modelsLoaded.push(shuffledModels[i]);
  }

  // GPU memory: based on GPU count (assume A100 80GB)
  const gpuMemoryTotalGb = gpuCount * 80;
  const gpuMemoryFreeGb = gpuMemoryTotalGb * (1 - gpuUtilization * 0.8);

  // Generate node ID and name using airport code and ISO region
  const nodeId = `node-${airport.code}-${String(index).padStart(3, '0')}`;
  const nodeName = `${airport.code}-${airport.iso}-${String(index).padStart(3, '0')}`;

  return {
    id: nodeId,
    name: nodeName,
    geohash: generateGeohash(lng, lat),
    position: [lng, lat],
    geozone: airport.iso, // Use ISO/RTO region as geozone
    energySource,
    batteryLevel,
    gpuUtilization,
    gpuMemoryFreeGb,
    gpuMemoryTotalGb,
    gpuCount,
    modelsLoaded,
    status,
    lastSeen: new Date(Date.now() - (status === 'stale' ? 300000 : random() * 60000)),
  };
}

/**
 * Generate mock nodes at freight airport locations
 */
export function generateMockNodes(count: number = 100, seed: number = 42): Node[] {
  const random = seededRandom(seed);
  const nodes: Node[] = [];

  for (let i = 0; i < count; i++) {
    nodes.push(generateNode(i, random));
  }

  return nodes;
}

/**
 * ORNL Frontier - The central hub supercomputer
 */
export const ORNL_FRONTIER_NODE: Node = {
  id: 'ornl-frontier',
  name: 'ORNL-FRONTIER',
  geohash: 'dn4h7c',
  position: [-84.2696, 35.9311], // Oak Ridge National Labs, TN
  geozone: 'TVA', // Tennessee Valley Authority
  energySource: 'cogen',
  batteryLevel: 0.95,
  gpuUtilization: 0.82,
  gpuMemoryFreeGb: 4800,
  gpuMemoryTotalGb: 32000, // Frontier has ~32 PB total memory
  gpuCount: 37632, // Frontier has 37,632 AMD MI250X GPUs
  modelsLoaded: ['llama-3.1-405b', 'mixtral-8x22b', 'deepseek-v3'],
  status: 'online',
  lastSeen: new Date(),
  isHub: true, // Special flag for hub node
};

/**
 * Pre-generated mock nodes for immediate use
 */
export const MOCK_NODES = [ORNL_FRONTIER_NODE, ...generateMockNodes(100)];
