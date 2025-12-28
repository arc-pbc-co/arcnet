/**
 * GlobeView - deck.gl globe visualization
 *
 * Displays nodes on an interactive 3D globe with:
 * - Dark background
 * - Nodes colored by energy source
 * - Size by GPU count
 * - Opacity fade for stale nodes
 * - Click to select nodes
 * - Animated cyan inference arcs with pulse effect
 * - Purple HPC transfer arcs with progress indication
 * - Arc lifecycle management (fade in/out)
 * - Performance optimizations for 1000+ nodes
 */

import { useCallback, useEffect, useState, useRef, useMemo } from 'react';
import DeckGL from '@deck.gl/react';
import { _GlobeView as DeckGlobeView } from '@deck.gl/core';
import { ScatterplotLayer, ArcLayer, SolidPolygonLayer, GeoJsonLayer } from '@deck.gl/layers';
import { COORDINATE_SYSTEM } from '@deck.gl/core';
import {
  useArcnetStore,
  VIEW_PRESETS,
  type ViewPresetKey,
} from '@/stores/arcnetStore';
import type { Node, InferenceArc, HpcTransfer } from '@/types/arcnet';
import { MOCK_NODES } from './mockNodes'; // Updated to use ISO/RTO regions
import { MockTrafficGenerator } from './mockTraffic';
import styles from './GlobeView.module.css';

// GeoJSON data URLs
const COUNTRIES_GEOJSON_URL = 'https://raw.githubusercontent.com/datasets/geo-countries/master/data/countries.geojson';
const US_STATES_GEOJSON_URL = 'https://raw.githubusercontent.com/PublicaMundi/MappingAPI/master/data/geojson/us-states.json';

// Energy source colors (RGBA)
const ENERGY_COLORS: Record<string, [number, number, number]> = {
  cogen: [68, 136, 255],   // Blue
  grid: [255, 136, 0],     // Orange
  battery: [0, 255, 136],  // Green
};

// Hub node color (bright purple/magenta for visibility)
const HUB_COLOR: [number, number, number] = [255, 100, 255];

// Status opacity multipliers
const STATUS_OPACITY: Record<string, number> = {
  online: 1.0,
  busy: 1.0,
  idle: 0.7,
  stale: 0.3,
  offline: 0.1,
};

// Arc colors (used in ArcLayer getSourceColor/getTargetColor)
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const _INFERENCE_ARC_COLOR: [number, number, number, number] = [0, 212, 255, 200]; // Cyan
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const _HPC_ARC_COLOR: [number, number, number, number] = [170, 68, 255, 200]; // Purple

// Priority width multipliers
const PRIORITY_WIDTH: Record<string, number> = {
  critical: 4,
  normal: 2,
  background: 1,
};

// Globe view instance with better resolution
const GLOBE_VIEW = new DeckGlobeView({
  id: 'globe',
  resolution: 10,
});

interface NodeTooltipInfo {
  node: Node;
  x: number;
  y: number;
}

// Arc with lifecycle state for fade in/out animations
interface ManagedArc extends InferenceArc {
  createdAt: number;
  expiresAt: number;
  opacity: number;
}

interface ManagedHpcTransfer extends HpcTransfer {
  createdAt: number;
  opacity: number;
}

export function GlobeView() {
  const [isLoaded, setIsLoaded] = useState(false);
  const [tooltip, setTooltip] = useState<NodeTooltipInfo | null>(null);
  const [animationTime, setAnimationTime] = useState(0);
  const [countriesData, setCountriesData] = useState<any>(null);
  const [usStatesData, setUsStatesData] = useState<any>(null);
  const trafficGeneratorRef = useRef<MockTrafficGenerator | null>(null);
  const lastUpdateRef = useRef<number>(Date.now());

  // Zustand store - state
  const nodes = useArcnetStore((state) => state.nodes);
  const inferenceArcs = useArcnetStore((state) => state.inferenceArcs);
  const hpcTransfers = useArcnetStore((state) => state.hpcTransfers);
  const viewState = useArcnetStore((state) => state.viewState);
  const selectedNodeId = useArcnetStore((state) => state.selectedNodeId);

  // Zustand store - actions
  const setViewState = useArcnetStore((state) => state.setViewState);
  const setSelectedNode = useArcnetStore((state) => state.setSelectedNode);
  const setNodes = useArcnetStore((state) => state.setNodes);
  const flyToPreset = useArcnetStore((state) => state.flyToPreset);
  const addInferenceArc = useArcnetStore((state) => state.addInferenceArc);
  const removeInferenceArc = useArcnetStore((state) => state.removeInferenceArc);
  const addHpcTransfer = useArcnetStore((state) => state.addHpcTransfer);
  const updateHpcTransfer = useArcnetStore((state) => state.updateHpcTransfer);
  const removeHpcTransfer = useArcnetStore((state) => state.removeHpcTransfer);

  // Animation loop for arc pulses and lifecycle management
  useEffect(() => {
    let animationFrameId: number;

    const animate = () => {
      const now = Date.now();
      const delta = now - lastUpdateRef.current;
      lastUpdateRef.current = now;

      // Update animation time (0-1 cycle for pulse effect)
      setAnimationTime((prev) => (prev + delta * 0.001) % 1);

      animationFrameId = requestAnimationFrame(animate);
    };

    animationFrameId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(animationFrameId);
  }, []);

  // Initialize with mock data - always use MOCK_NODES on mount
  useEffect(() => {
    console.log('[GlobeView] Current nodes in store:', nodes.length);
    console.log('[GlobeView] MOCK_NODES available:', MOCK_NODES.length);
    console.log('[GlobeView] Hub nodes in MOCK_NODES:', MOCK_NODES.filter(n => n.isHub));

    // Always set nodes from MOCK_NODES on initial mount
    setNodes(MOCK_NODES);

    const timer = setTimeout(() => setIsLoaded(true), 300);
    return () => clearTimeout(timer);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // Only run once on mount

  // Load GeoJSON data for map boundaries
  useEffect(() => {
    // Load countries
    fetch(COUNTRIES_GEOJSON_URL)
      .then((response) => response.json())
      .then((data) => setCountriesData(data))
      .catch((error) => console.error('Failed to load countries GeoJSON:', error));

    // Load US states
    fetch(US_STATES_GEOJSON_URL)
      .then((response) => response.json())
      .then((data) => setUsStatesData(data))
      .catch((error) => console.error('Failed to load US states GeoJSON:', error));
  }, []);

  // Start/stop traffic generator
  useEffect(() => {
    if (nodes.length === 0) return;

    const generator = new MockTrafficGenerator(
      () => useArcnetStore.getState().nodes,
      addInferenceArc,
      removeInferenceArc,
      addHpcTransfer,
      updateHpcTransfer,
      removeHpcTransfer,
      () => useArcnetStore.getState().inferenceArcs,
      () => useArcnetStore.getState().hpcTransfers,
    );

    generator.start(1000, 8000); // Slower: Inference every 1s, HPC every 8s
    trafficGeneratorRef.current = generator;

    return () => {
      generator.stop();
      trafficGeneratorRef.current = null;
    };
  }, [
    nodes.length,
    addInferenceArc,
    removeInferenceArc,
    addHpcTransfer,
    updateHpcTransfer,
    removeHpcTransfer,
  ]);

  // Handle view state changes
  const onViewStateChange = useCallback(
    ({ viewState: newViewState }: { viewState: unknown }) => {
      const vs = newViewState as Record<string, unknown>;
      setViewState({
        longitude: vs.longitude as number,
        latitude: vs.latitude as number,
        zoom: vs.zoom as number,
        pitch: vs.pitch as number,
        bearing: vs.bearing as number,
      });
    },
    [setViewState]
  );

  // Handle node click
  const onNodeClick = useCallback(
    (info: { object?: Node }) => {
      if (info.object) {
        setSelectedNode(info.object.id);
      } else {
        setSelectedNode(null);
      }
    },
    [setSelectedNode]
  );

  // Handle node hover
  const onNodeHover = useCallback(
    (info: { object?: Node; x?: number; y?: number }) => {
      if (info.object && info.x !== undefined && info.y !== undefined) {
        setTooltip({ node: info.object, x: info.x, y: info.y });
      } else {
        setTooltip(null);
      }
    },
    []
  );

  // Zoom controls
  const handleZoomIn = useCallback(() => {
    setViewState({
      ...viewState,
      zoom: Math.min(viewState.zoom + 0.5, 10),
      transitionDuration: 300,
    });
  }, [viewState, setViewState]);

  const handleZoomOut = useCallback(() => {
    setViewState({
      ...viewState,
      zoom: Math.max(viewState.zoom - 0.5, 0.5),
      transitionDuration: 300,
    });
  }, [viewState, setViewState]);

  const handleResetView = useCallback(() => {
    flyToPreset('northAmerica');
  }, [flyToPreset]);

  // Manage arc lifecycle with fade in/out
  const managedInferenceArcs = useMemo<ManagedArc[]>(() => {
    const now = Date.now();
    const ARC_LIFETIME = 2000; // 2 seconds
    const FADE_IN_DURATION = 200; // 200ms fade in
    const FADE_OUT_DURATION = 500; // 500ms fade out

    return inferenceArcs.map((arc) => {
      const createdAt = arc.timestamp.getTime();
      const expiresAt = createdAt + ARC_LIFETIME;
      const age = now - createdAt;
      const timeUntilExpiry = expiresAt - now;

      let opacity = 1;
      if (age < FADE_IN_DURATION) {
        opacity = age / FADE_IN_DURATION;
      } else if (timeUntilExpiry < FADE_OUT_DURATION) {
        opacity = Math.max(0, timeUntilExpiry / FADE_OUT_DURATION);
      }

      return {
        ...arc,
        createdAt,
        expiresAt,
        opacity,
      };
    }).filter((arc) => arc.opacity > 0);
  }, [inferenceArcs, animationTime]); // Re-compute on animation frame

  // Manage HPC transfer opacity
  const managedHpcTransfers = useMemo<ManagedHpcTransfer[]>(() => {
    const now = Date.now();
    const FADE_IN_DURATION = 300;

    return hpcTransfers.map((transfer) => {
      const createdAt = transfer.timestamp.getTime();
      const age = now - createdAt;

      let opacity = 1;
      if (age < FADE_IN_DURATION) {
        opacity = age / FADE_IN_DURATION;
      }

      return {
        ...transfer,
        createdAt,
        opacity,
      };
    });
  }, [hpcTransfers, animationTime]);

  // Create layers with performance optimizations
  const layers = useMemo(() => [
    // Background sphere to make Earth visible
    new SolidPolygonLayer({
      id: 'earth-sphere',
      data: [
        {
          polygon: [
            [-180, 90],
            [180, 90],
            [180, -90],
            [-180, -90],
            [-180, 90],
          ],
        },
      ],
      getPolygon: (d: any) => d.polygon,
      filled: true,
      getFillColor: [10, 30, 20, 255], // Darker green-tinted Earth
      stroked: true,
      getLineColor: [0, 150, 75, 80], // Brighter green border for visibility
      getLineWidth: 2,
      coordinateSystem: COORDINATE_SYSTEM.LNGLAT,
    }),

    // Country boundaries layer
    countriesData && new GeoJsonLayer({
      id: 'countries',
      data: countriesData,
      pickable: false,
      stroked: true,
      filled: false,
      lineWidthMinPixels: 1,
      getLineColor: [0, 200, 100, 120], // Bright green borders
      getLineWidth: 1,
    }),

    // US states boundaries layer
    usStatesData && new GeoJsonLayer({
      id: 'us-states',
      data: usStatesData,
      pickable: false,
      stroked: true,
      filled: false,
      lineWidthMinPixels: 1,
      getLineColor: [0, 255, 150, 150], // Brighter green for states
      getLineWidth: 2,
    }),

    // Regular node scatter layer (non-hub nodes)
    new ScatterplotLayer<Node>({
      id: 'nodes',
      data: nodes.filter((n) => !n.isHub),
      pickable: true,
      opacity: 0.8,
      stroked: true,
      filled: true,
      radiusScale: 1,
      radiusMinPixels: 4,
      radiusMaxPixels: 20,
      lineWidthMinPixels: 1,
      getPosition: (d: Node) => d.position,
      getRadius: (d: Node) => Math.sqrt(d.gpuCount) * 500,
      getFillColor: (d: Node) => {
        const baseColor = ENERGY_COLORS[d.energySource] || [100, 100, 100];
        const opacity = STATUS_OPACITY[d.status] || 1.0;
        return [...baseColor, Math.round(255 * opacity)] as [number, number, number, number];
      },
      getLineColor: (d: Node) =>
        d.id === selectedNodeId ? [0, 255, 65, 255] : [0, 100, 50, 150],
      getLineWidth: (d: Node) => d.id === selectedNodeId ? 3 : 1,
      onClick: onNodeClick,
      onHover: onNodeHover,
      updateTriggers: {
        getLineColor: [selectedNodeId],
        getLineWidth: [selectedNodeId],
      },
    }),

    // Hub node layer (ORNL Frontier) - rendered larger and brighter
    new ScatterplotLayer<Node>({
      id: 'hub-nodes',
      data: nodes.filter((n) => n.isHub),
      pickable: true,
      opacity: 1.0,
      stroked: true,
      filled: true,
      radiusScale: 1,
      radiusMinPixels: 8,
      radiusMaxPixels: 20,
      lineWidthMinPixels: 2,
      getPosition: (d: Node) => d.position,
      getRadius: () => 4000, // Fixed radius for hub (half of previous)
      getFillColor: () => [...HUB_COLOR, 255] as [number, number, number, number],
      getLineColor: (d: Node) =>
        d.id === selectedNodeId ? [255, 255, 255, 255] : [255, 200, 255, 200],
      getLineWidth: (d: Node) => d.id === selectedNodeId ? 4 : 2,
      onClick: onNodeClick,
      onHover: onNodeHover,
      updateTriggers: {
        getLineColor: [selectedNodeId],
        getLineWidth: [selectedNodeId],
      },
    }),

    // Inference request arcs (cyan) with animated pulse
    new ArcLayer<ManagedArc>({
      id: 'inference-arcs',
      data: managedInferenceArcs,
      pickable: false,
      getSourcePosition: (d: ManagedArc) => d.source,
      getTargetPosition: (d: ManagedArc) => d.target,
      getSourceColor: (d: ManagedArc) => {
        const baseOpacity = Math.round(200 * d.opacity);
        // Add pulse effect based on animation time
        const pulse = Math.sin(animationTime * Math.PI * 2) * 0.3 + 0.7;
        return [0, 212, 255, Math.round(baseOpacity * pulse)];
      },
      getTargetColor: (d: ManagedArc) => [0, 212, 255, Math.round(50 * d.opacity)],
      getWidth: (d: ManagedArc) => PRIORITY_WIDTH[d.priority] || 2,
      getTilt: 15,
      getHeight: 0.5,
      updateTriggers: {
        getSourceColor: [animationTime],
        getTargetColor: [managedInferenceArcs.length],
      },
    }),

    // HPC transfer arcs (purple) with progress indication
    new ArcLayer<ManagedHpcTransfer>({
      id: 'hpc-arcs',
      data: managedHpcTransfers,
      pickable: true,
      getSourcePosition: (d: ManagedHpcTransfer) => d.source,
      getTargetPosition: (d: ManagedHpcTransfer) => d.target,
      getSourceColor: (d: ManagedHpcTransfer) => {
        const baseOpacity = Math.round(200 * d.opacity);
        return [170, 68, 255, baseOpacity];
      },
      getTargetColor: (d: ManagedHpcTransfer) => {
        const baseOpacity = Math.round(100 * d.opacity);
        return [170, 68, 255, baseOpacity];
      },
      getWidth: (d: ManagedHpcTransfer) => {
        // Width based on dataset size, with minimum for visibility
        const baseWidth = Math.min(d.datasetSizeGb / 100, 10);
        return Math.max(baseWidth, 2);
      },
      getTilt: 30,
      getHeight: 0.7,
      updateTriggers: {
        getSourceColor: [managedHpcTransfers.length],
        getTargetColor: [managedHpcTransfers.length],
      },
    }),
  ].filter(Boolean), [nodes, managedInferenceArcs, managedHpcTransfers, selectedNodeId, animationTime, onNodeClick, onNodeHover, countriesData, usStatesData]);

  return (
    <div className={styles.container}>
      {!isLoaded && (
        <div className={styles.loading}>
          <span className={styles.loadingText}>Initializing Globe...</span>
        </div>
      )}

      <DeckGL
        views={GLOBE_VIEW}
        viewState={viewState}
        onViewStateChange={onViewStateChange}
        controller={{
          scrollZoom: { speed: 0.01, smooth: true },
          dragRotate: true,
          dragPan: true,
          keyboard: true,
          doubleClickZoom: true,
          touchZoom: true,
          touchRotate: true,
        }}
        layers={layers}
        style={{ background: '#000000' }}
      />

      {/* Custom tooltip */}
      {tooltip && (
        <div
          className={`${styles.tooltip} ${tooltip.node.isHub ? styles.tooltipHub : ''}`}
          style={{ left: tooltip.x + 10, top: tooltip.y + 10 }}
        >
          <div className={styles.tooltipTitle}>
            {tooltip.node.isHub && <span className={styles.hubBadge}>HUB</span>}
            {tooltip.node.name}
          </div>
          {tooltip.node.isHub && (
            <div className={styles.tooltipSubtitle}>Oak Ridge National Laboratory</div>
          )}
          <div className={styles.tooltipRow}>
            <span>Status</span>
            <span className={styles.tooltipValue}>{tooltip.node.status}</span>
          </div>
          <div className={styles.tooltipRow}>
            <span>Energy</span>
            <span className={styles.tooltipValue}>{tooltip.node.energySource.toUpperCase()}</span>
          </div>
          <div className={styles.tooltipRow}>
            <span>GPUs</span>
            <span className={styles.tooltipValue}>
              {tooltip.node.gpuCount.toLocaleString()}x {tooltip.node.isHub ? 'MI250X' : ''}
            </span>
          </div>
          <div className={styles.tooltipRow}>
            <span>GPU Util</span>
            <span className={styles.tooltipValue}>
              {Math.round(tooltip.node.gpuUtilization * 100)}%
            </span>
          </div>
          {tooltip.node.isHub && (
            <>
              <div className={styles.tooltipRow}>
                <span>Memory</span>
                <span className={styles.tooltipValue}>
                  {(tooltip.node.gpuMemoryTotalGb / 1000).toFixed(0)} PB
                </span>
              </div>
              <div className={styles.tooltipRow}>
                <span>Models</span>
                <span className={styles.tooltipValue}>
                  {tooltip.node.modelsLoaded.length}
                </span>
              </div>
            </>
          )}
        </div>
      )}

      {/* Zoom controls */}
      <div className={styles.zoomControls}>
        <button
          className={styles.zoomButton}
          onClick={handleZoomIn}
          title="Zoom In"
        >
          [+]
        </button>
        <button
          className={styles.zoomButton}
          onClick={handleZoomOut}
          title="Zoom Out"
        >
          [−]
        </button>
        <button
          className={styles.zoomButton}
          onClick={handleResetView}
          title="Reset View (USA)"
        >
          [⌂]
        </button>
      </div>

      {/* View preset controls */}
      <div className={styles.viewControls}>
        {(Object.keys(VIEW_PRESETS) as ViewPresetKey[]).map((preset) => (
          <button
            key={preset}
            className={styles.viewButton}
            onClick={() => flyToPreset(preset)}
          >
            {preset === 'ornl' ? 'ORNL' : preset.replace(/([A-Z])/g, ' $1').trim()}
          </button>
        ))}
      </div>

      {/* ORNL marker */}
      <div className={styles.ornlMarker}>
        <span className={styles.ornlTitle}>ORNL FRONTIER</span>
        <span className={styles.ornlPulse} />
      </div>

      {/* Legend */}
      <div className={styles.legend}>
        <span className={styles.legendTitle}>Node Types</span>
        <div className={styles.legendItem}>
          <span className={`${styles.legendDot} ${styles.legendDotHub}`} />
          <span>HUB (ORNL)</span>
        </div>
        <span className={styles.legendTitle} style={{ marginTop: '8px' }}>Energy Source</span>
        <div className={styles.legendItem}>
          <span className={`${styles.legendDot} ${styles.legendDotCogen}`} />
          <span>COGEN</span>
        </div>
        <div className={styles.legendItem}>
          <span className={`${styles.legendDot} ${styles.legendDotGrid}`} />
          <span>Grid</span>
        </div>
        <div className={styles.legendItem}>
          <span className={`${styles.legendDot} ${styles.legendDotBattery}`} />
          <span>Battery</span>
        </div>
        <span className={styles.legendTitle} style={{ marginTop: '8px' }}>Traffic</span>
        <div className={styles.legendItem}>
          <span className={styles.legendDot} style={{ background: 'rgb(0, 212, 255)', boxShadow: '0 0 6px rgba(0, 212, 255, 0.6)' }} />
          <span>Inference</span>
        </div>
        <div className={styles.legendItem}>
          <span className={styles.legendDot} style={{ background: 'rgb(170, 68, 255)', boxShadow: '0 0 6px rgba(170, 68, 255, 0.6)' }} />
          <span>HPC Transfer</span>
        </div>
      </div>

      {/* Traffic counter */}
      <div className={styles.trafficCounter}>
        <span className={styles.trafficLabel}>Active</span>
        <span className={styles.trafficValue}>
          <span style={{ color: 'rgb(0, 212, 255)' }}>{inferenceArcs.length}</span>
          {' / '}
          <span style={{ color: 'rgb(170, 68, 255)' }}>{hpcTransfers.length}</span>
        </span>
      </div>
    </div>
  );
}

export default GlobeView;
