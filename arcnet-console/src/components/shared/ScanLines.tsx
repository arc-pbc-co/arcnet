/**
 * ScanLines Component - CRT scan line overlay effect
 *
 * Provides the retro CRT monitor aesthetic with configurable
 * scan lines, flicker, vignette, and noise effects.
 */

import styles from './ScanLines.module.css';

export type ScanLineIntensity = 'light' | 'medium' | 'heavy';

export interface ScanLinesProps {
  /** Enable scan lines */
  enabled?: boolean;
  /** Scan line intensity */
  intensity?: ScanLineIntensity;
  /** Enable subtle screen flicker */
  flicker?: boolean;
  /** Enable vignette (darkened edges) */
  vignette?: boolean;
  /** Enable RGB shift effect on edges */
  rgbShift?: boolean;
  /** Enable animated scan line movement */
  animated?: boolean;
  /** Enable phosphor glow in center */
  phosphorGlow?: boolean;
  /** Enable static noise overlay */
  noise?: boolean;
  /** Enable occasional glitch effect */
  glitch?: boolean;
  /** Additional CSS class */
  className?: string;
}

const intensityClasses: Record<ScanLineIntensity, string> = {
  light: styles.intensityLight,
  medium: styles.intensityMedium,
  heavy: styles.intensityHeavy,
};

export function ScanLines({
  enabled = true,
  intensity = 'medium',
  flicker = true,
  vignette = false,
  rgbShift = false,
  animated = false,
  phosphorGlow = false,
  noise = false,
  glitch = false,
  className = '',
}: ScanLinesProps) {
  if (!enabled) return null;

  const overlayClasses = [
    styles.overlay,
    styles.scanlines,
    intensityClasses[intensity],
    flicker && styles.flicker,
    vignette && styles.vignette,
    rgbShift && styles.rgbShift,
    animated && styles.animated,
    phosphorGlow && styles.phosphorGlow,
    noise && styles.noise,
    glitch && styles.glitch,
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return <div className={overlayClasses} aria-hidden="true" />;
}

/**
 * Preset: Minimal CRT effect (just scan lines)
 */
export function ScanLinesMinimal() {
  return <ScanLines intensity="light" flicker={false} />;
}

/**
 * Preset: Standard CRT effect
 */
export function ScanLinesStandard() {
  return <ScanLines intensity="medium" flicker={true} />;
}

/**
 * Preset: Heavy retro CRT effect
 */
export function ScanLinesRetro() {
  return (
    <ScanLines
      intensity="heavy"
      flicker={true}
      vignette={true}
      phosphorGlow={true}
      noise={true}
    />
  );
}

/**
 * Preset: Cyberpunk glitch effect
 */
export function ScanLinesCyberpunk() {
  return (
    <ScanLines
      intensity="medium"
      flicker={true}
      rgbShift={true}
      glitch={true}
    />
  );
}

export default ScanLines;
