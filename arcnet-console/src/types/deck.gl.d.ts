/**
 * Type declarations for deck.gl v8
 */

declare module '@deck.gl/react' {
  import { ComponentType } from 'react';

  export interface DeckGLProps {
    views?: unknown;
    viewState?: unknown;
    initialViewState?: unknown;
    onViewStateChange?: (params: { viewState: unknown }) => void;
    controller?: boolean | unknown;
    layers?: unknown[];
    getTooltip?: ((info: unknown) => unknown) | null;
    children?: React.ReactNode;
  }

  const DeckGL: ComponentType<DeckGLProps>;
  export default DeckGL;
}

declare module '@deck.gl/core' {
  export class _GlobeView {
    constructor(options?: { id?: string; resolution?: number });
  }

  // Alias for backward compatibility
  export { _GlobeView as GlobeView };

  export class MapView {
    constructor(options?: { id?: string });
  }

  export class OrthographicView {
    constructor(options?: { id?: string });
  }
}

declare module '@deck.gl/layers' {
  interface LayerProps<D = unknown> {
    id: string;
    data?: D[];
    pickable?: boolean;
    visible?: boolean;
    opacity?: number;
    updateTriggers?: Record<string, unknown>;
  }

  interface ScatterplotLayerProps<D = unknown> extends LayerProps<D> {
    getPosition: (d: D) => [number, number] | [number, number, number];
    getRadius?: number | ((d: D) => number);
    getFillColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    getLineColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    getLineWidth?: number | ((d: D) => number);
    stroked?: boolean;
    filled?: boolean;
    radiusScale?: number;
    radiusMinPixels?: number;
    radiusMaxPixels?: number;
    radiusUnits?: 'meters' | 'pixels';
    lineWidthMinPixels?: number;
    lineWidthMaxPixels?: number;
    lineWidthUnits?: 'meters' | 'pixels';
    onClick?: (info: { object?: D; x?: number; y?: number }) => void;
    onHover?: (info: { object?: D; x?: number; y?: number }) => void;
  }

  export class ScatterplotLayer<D = unknown> {
    constructor(props: ScatterplotLayerProps<D>);
  }

  interface ArcLayerProps<D = unknown> extends LayerProps<D> {
    getSourcePosition: (d: D) => [number, number] | [number, number, number];
    getTargetPosition: (d: D) => [number, number] | [number, number, number];
    getSourceColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    getTargetColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    getWidth?: number | ((d: D) => number);
    getHeight?: number;
    getTilt?: number;
    widthMinPixels?: number;
    widthMaxPixels?: number;
    widthUnits?: 'meters' | 'pixels';
  }

  export class ArcLayer<D = unknown> {
    constructor(props: ArcLayerProps<D>);
  }

  interface SolidPolygonLayerProps<D = unknown> extends LayerProps<D> {
    getPolygon: (d: D) => number[][] | number[][][];
    getFillColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    getLineColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    filled?: boolean;
    extruded?: boolean;
    wireframe?: boolean;
    getElevation?: number | ((d: D) => number);
    elevationScale?: number;
  }

  export class SolidPolygonLayer<D = unknown> {
    constructor(props: SolidPolygonLayerProps<D>);
  }

  interface GeoJsonLayerProps<D = unknown> extends LayerProps<D> {
    stroked?: boolean;
    filled?: boolean;
    extruded?: boolean;
    getFillColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    getLineColor?: [number, number, number, number] | ((d: D) => [number, number, number, number]);
    getLineWidth?: number | ((d: D) => number);
    lineWidthMinPixels?: number;
  }

  export class GeoJsonLayer<D = unknown> {
    constructor(props: GeoJsonLayerProps<D>);
  }
}

declare module '@deck.gl/geo-layers' {
  import { LayerProps } from '@deck.gl/layers';

  interface TileLayerProps extends LayerProps {
    getTileData?: (tile: unknown) => unknown;
    renderSubLayers?: (props: unknown) => unknown;
    minZoom?: number;
    maxZoom?: number;
    tileSize?: number;
  }

  export class TileLayer {
    constructor(props: TileLayerProps);
  }
}
