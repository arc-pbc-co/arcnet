/**
 * ARCNet Console - Zustand Store with Immer
 *
 * Global state management for the operations dashboard.
 * Handles nodes, inference traffic, HPC transfers, events, and view state.
 */

import { create } from 'zustand';
import { devtools, subscribeWithSelector } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';
import type {
  Node,
  InferenceArc,
  HpcTransfer,
  ConsoleEvent,
  GlobalStats,
  GeozoneStats,
} from '@/types/arcnet';

// =============================================================================
// View Presets - Globe camera positions
// =============================================================================

export const VIEW_PRESETS = {
  global: {
    longitude: 0,
    latitude: 20,
    zoom: 1.2,
    pitch: 0,
    bearing: 0,
  },
  northAmerica: {
    longitude: -98.5795,
    latitude: 39.8283,
    zoom: 2.8,
    pitch: 45,
    bearing: 0,
  },
  europe: {
    longitude: 10.4515,
    latitude: 51.1657,
    zoom: 3.5,
    pitch: 45,
    bearing: 0,
  },
  asia: {
    longitude: 104.1954,
    latitude: 35.8617,
    zoom: 3,
    pitch: 45,
    bearing: 0,
  },
  ornl: {
    longitude: -84.2696,
    latitude: 35.9311,
    zoom: 8,
    pitch: 60,
    bearing: -20,
  },
} as const;

export type ViewPresetKey = keyof typeof VIEW_PRESETS;

// =============================================================================
// View State Interface
// =============================================================================

export interface ViewState {
  longitude: number;
  latitude: number;
  zoom: number;
  pitch: number;
  bearing: number;
  transitionDuration?: number;
}

// =============================================================================
// Store State Interface
// =============================================================================

export interface ArcnetState {
  // Node state
  nodes: Node[];
  selectedNodeId: string | null;

  // Traffic visualization
  inferenceArcs: InferenceArc[];
  hpcTransfers: HpcTransfer[];

  // Event log
  events: ConsoleEvent[];
  maxEvents: number;

  // Global statistics
  globalStats: GlobalStats | null;
  geozoneStats: Map<string, GeozoneStats>;

  // View state (deck.gl camera)
  viewState: ViewState;

  // UI state
  isConnected: boolean;
  isPaused: boolean;
  showScanlines: boolean;

  // Command line
  commandHistory: string[];
  commandHistoryIndex: number;
}

// =============================================================================
// Store Actions Interface
// =============================================================================

export interface ArcnetActions {
  // Node actions
  setNodes: (nodes: Node[]) => void;
  updateNode: (nodeId: string, updates: Partial<Node>) => void;
  addNode: (node: Node) => void;
  removeNode: (nodeId: string) => void;
  setSelectedNode: (nodeId: string | null) => void;

  // Inference arc actions
  addInferenceArc: (arc: InferenceArc) => void;
  removeInferenceArc: (id: string) => void;
  updateInferenceArc: (id: string, updates: Partial<InferenceArc>) => void;
  clearInferenceArcs: () => void;

  // HPC transfer actions
  addHpcTransfer: (transfer: HpcTransfer) => void;
  updateHpcTransfer: (id: string, updates: Partial<HpcTransfer>) => void;
  removeHpcTransfer: (id: string) => void;
  clearHpcTransfers: () => void;

  // Event actions
  addEvent: (event: ConsoleEvent) => void;
  clearEvents: () => void;

  // Stats actions
  setGlobalStats: (stats: GlobalStats) => void;
  setGeozoneStats: (geozone: string, stats: GeozoneStats) => void;

  // View state actions
  setViewState: (viewState: Partial<ViewState>) => void;
  flyToNode: (nodeId: string) => void;
  flyToPreset: (preset: ViewPresetKey) => void;

  // UI actions
  setConnected: (connected: boolean) => void;
  togglePause: () => void;
  toggleScanlines: () => void;

  // Command line actions
  addToCommandHistory: (command: string) => void;
  setCommandHistoryIndex: (index: number) => void;

  // Reset
  reset: () => void;
}

// =============================================================================
// Initial State
// =============================================================================

const initialState: ArcnetState = {
  nodes: [],
  selectedNodeId: null,
  inferenceArcs: [],
  hpcTransfers: [],
  events: [],
  maxEvents: 1000,
  globalStats: null,
  geozoneStats: new Map(),
  viewState: {
    longitude: -84.2696, // Oak Ridge National Labs, TN
    latitude: 35.9311,
    zoom: 2.2,
    pitch: 45,
    bearing: 0,
  },
  isConnected: false,
  isPaused: false,
  showScanlines: true,
  commandHistory: [],
  commandHistoryIndex: -1,
};

// =============================================================================
// Store Creation with Immer
// =============================================================================

export const useArcnetStore = create<ArcnetState & ArcnetActions>()(
  devtools(
    subscribeWithSelector(
      immer((set, get) => ({
        ...initialState,

        // =====================================================================
        // Node Actions
        // =====================================================================

        setNodes: (nodes) =>
          set((state) => {
            state.nodes = nodes;
          }),

        updateNode: (nodeId, updates) =>
          set((state) => {
            const index = state.nodes.findIndex((n) => n.id === nodeId);
            if (index !== -1) {
              Object.assign(state.nodes[index], updates);
            }
          }),

        addNode: (node) =>
          set((state) => {
            state.nodes.push(node);
          }),

        removeNode: (nodeId) =>
          set((state) => {
            state.nodes = state.nodes.filter((n) => n.id !== nodeId);
            if (state.selectedNodeId === nodeId) {
              state.selectedNodeId = null;
            }
          }),

        setSelectedNode: (nodeId) =>
          set((state) => {
            state.selectedNodeId = nodeId;
          }),

        // =====================================================================
        // Inference Arc Actions
        // =====================================================================

        addInferenceArc: (arc) =>
          set((state) => {
            state.inferenceArcs.push(arc);
            // Keep last 200 arcs for performance
            if (state.inferenceArcs.length > 200) {
              state.inferenceArcs = state.inferenceArcs.slice(-200);
            }
          }),

        removeInferenceArc: (id) =>
          set((state) => {
            state.inferenceArcs = state.inferenceArcs.filter((a) => a.id !== id);
          }),

        updateInferenceArc: (id, updates) =>
          set((state) => {
            const index = state.inferenceArcs.findIndex((a) => a.id === id);
            if (index !== -1) {
              Object.assign(state.inferenceArcs[index], updates);
            }
          }),

        clearInferenceArcs: () =>
          set((state) => {
            state.inferenceArcs = [];
          }),

        // =====================================================================
        // HPC Transfer Actions
        // =====================================================================

        addHpcTransfer: (transfer) =>
          set((state) => {
            state.hpcTransfers.push(transfer);
          }),

        updateHpcTransfer: (id, updates) =>
          set((state) => {
            const index = state.hpcTransfers.findIndex((t) => t.id === id);
            if (index !== -1) {
              Object.assign(state.hpcTransfers[index], updates);
            }
          }),

        removeHpcTransfer: (id) =>
          set((state) => {
            state.hpcTransfers = state.hpcTransfers.filter((t) => t.id !== id);
          }),

        clearHpcTransfers: () =>
          set((state) => {
            state.hpcTransfers = [];
          }),

        // =====================================================================
        // Event Actions
        // =====================================================================

        addEvent: (event) =>
          set((state) => {
            state.events.unshift(event);
            // Keep last maxEvents
            if (state.events.length > state.maxEvents) {
              state.events = state.events.slice(0, state.maxEvents);
            }
          }),

        clearEvents: () =>
          set((state) => {
            state.events = [];
          }),

        // =====================================================================
        // Stats Actions
        // =====================================================================

        setGlobalStats: (stats) =>
          set((state) => {
            state.globalStats = stats;
          }),

        setGeozoneStats: (geozone, stats) =>
          set((state) => {
            state.geozoneStats.set(geozone, stats);
          }),

        // =====================================================================
        // View State Actions
        // =====================================================================

        setViewState: (viewState) =>
          set((state) => {
            Object.assign(state.viewState, viewState);
          }),

        flyToNode: (nodeId) => {
          const state = get();
          const node = state.nodes.find((n) => n.id === nodeId);
          if (node) {
            // Position is [longitude, latitude] tuple
            const [longitude, latitude] = node.position;
            set((state) => {
              state.viewState = {
                longitude,
                latitude,
                zoom: 6,
                pitch: 45,
                bearing: 0,
                transitionDuration: 1500,
              };
              state.selectedNodeId = nodeId;
            });
          }
        },

        flyToPreset: (preset) =>
          set((state) => {
            state.viewState = {
              ...VIEW_PRESETS[preset],
              transitionDuration: 2000,
            };
          }),

        // =====================================================================
        // UI Actions
        // =====================================================================

        setConnected: (connected) =>
          set((state) => {
            state.isConnected = connected;
          }),

        togglePause: () =>
          set((state) => {
            state.isPaused = !state.isPaused;
          }),

        toggleScanlines: () =>
          set((state) => {
            state.showScanlines = !state.showScanlines;
          }),

        // =====================================================================
        // Command Line Actions
        // =====================================================================

        addToCommandHistory: (command) =>
          set((state) => {
            state.commandHistory.push(command);
            // Keep last 100 commands
            if (state.commandHistory.length > 100) {
              state.commandHistory = state.commandHistory.slice(-100);
            }
            state.commandHistoryIndex = -1;
          }),

        setCommandHistoryIndex: (index) =>
          set((state) => {
            state.commandHistoryIndex = index;
          }),

        // =====================================================================
        // Reset
        // =====================================================================

        reset: () => set(() => initialState),
      }))
    ),
    { name: 'arcnet-store' }
  )
);

// =============================================================================
// Computed Selectors
// =============================================================================

/**
 * Get all online nodes (status: online or busy)
 */
export const getOnlineNodes = (state: ArcnetState): Node[] =>
  state.nodes.filter((node) => node.status === 'online' || node.status === 'busy');

/**
 * Get all nodes powered by cogen (colocated generation)
 */
export const getCogenNodes = (state: ArcnetState): Node[] =>
  state.nodes.filter((node) => node.energySource === 'cogen');

/**
 * Get nodes filtered by geozone
 */
export const getNodesByGeozone = (state: ArcnetState, geozone: string): Node[] =>
  state.nodes.filter((node) => node.geozone === geozone);

/**
 * Get the currently selected node object
 */
export const getSelectedNode = (state: ArcnetState): Node | undefined =>
  state.nodes.find((node) => node.id === state.selectedNodeId);

/**
 * Get nodes by energy source
 */
export const getNodesByEnergySource = (
  state: ArcnetState,
  source: 'cogen' | 'grid' | 'battery'
): Node[] => state.nodes.filter((node) => node.energySource === source);

/**
 * Get active HPC transfers
 */
export const getActiveHpcTransfers = (state: ArcnetState): HpcTransfer[] =>
  state.hpcTransfers.filter(
    (t) => t.status === 'transferring' || t.status === 'pending' || t.status === 'running'
  );

/**
 * Get nodes with high GPU utilization (>80%)
 */
export const getBusyNodes = (state: ArcnetState): Node[] =>
  state.nodes.filter((node) => node.gpuUtilization > 0.8);

/**
 * Get nodes with low battery (<20%)
 */
export const getLowBatteryNodes = (state: ArcnetState): Node[] =>
  state.nodes.filter((node) => node.batteryLevel < 0.2);

/**
 * Get unique geozones from nodes
 */
export const getGeozones = (state: ArcnetState): string[] =>
  [...new Set(state.nodes.map((node) => node.geozone))];

/**
 * Get event counts by type
 */
export const getEventCountsByType = (
  state: ArcnetState
): Record<string, number> =>
  state.events.reduce(
    (acc, event) => {
      acc[event.type] = (acc[event.type] || 0) + 1;
      return acc;
    },
    {} as Record<string, number>
  );

// =============================================================================
// Selector Hooks (for convenience)
// =============================================================================

export const useNodes = () => useArcnetStore((state) => state.nodes);
export const useSelectedNodeId = () => useArcnetStore((state) => state.selectedNodeId);
export const useSelectedNode = () => useArcnetStore((state) => getSelectedNode(state));
export const useInferenceArcs = () => useArcnetStore((state) => state.inferenceArcs);
export const useHpcTransfers = () => useArcnetStore((state) => state.hpcTransfers);
export const useEvents = () => useArcnetStore((state) => state.events);
export const useViewState = () => useArcnetStore((state) => state.viewState);
export const useGlobalStats = () => useArcnetStore((state) => state.globalStats);
export const useIsConnected = () => useArcnetStore((state) => state.isConnected);
export const useIsPaused = () => useArcnetStore((state) => state.isPaused);
export const useShowScanlines = () => useArcnetStore((state) => state.showScanlines);
