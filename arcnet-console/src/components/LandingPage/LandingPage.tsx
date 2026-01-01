/**
 * LandingPage Component - Marketing landing page
 *
 * Simple marketing page with terminal aesthetic that leads to the console login.
 */

import { useState } from 'react';
import { TypedText } from '@/components/shared';
import styles from './LandingPage.module.css';

// ASCII art logo (same as login screen)
const ASCII_LOGO = `
    _    ____   ____ _   _      _
   / \\  |  _ \\ / ___| \\ | | ___| |_
  / _ \\ | |_) | |   |  \\| |/ _ \\ __|
 / ___ \\|  _ <| |___| |\\  |  __/ |_
/_/   \\_\\_| \\_\\\\____|_| \\_|\\___|\\__|
`;

const TAGLINE = 'Distributed AI inference at the edge of possibility.';

const FEATURES = [
  { icon: '>', label: 'DISTRIBUTED AI INFERENCE' },
  { icon: '>', label: 'REAL-TIME TELEMETRY' },
  { icon: '>', label: 'SECURE NETWORK OPS' },
];

export interface LandingPageProps {
  /** Callback when user clicks to enter the console */
  onEnterConsole: () => void;
}

export function LandingPage({ onEnterConsole }: LandingPageProps) {
  const [taglineComplete, setTaglineComplete] = useState(false);

  return (
    <div className={styles.container}>
      {/* Scanlines overlay */}
      <div className={styles.scanlines} />

      {/* Vignette effect */}
      <div className={styles.vignette} />

      <div className={styles.content}>
        {/* ASCII Logo */}
        <pre className={styles.logo}>{ASCII_LOGO}</pre>

        {/* Main Title */}
        <h1 className={styles.title}>
          <span className={styles.titleLine}>DISTRIBUTED AI</span>
          <span className={styles.titleLine}>INFERENCE NETWORK</span>
        </h1>

        {/* Divider */}
        <div className={styles.divider}>
          {'═'.repeat(40)}
        </div>

        {/* Animated Tagline */}
        <div className={styles.tagline}>
          <TypedText
            text={TAGLINE}
            speed={30}
            delay={500}
            color="cyan"
            size="md"
            showCursor={true}
            hideCursorOnComplete={true}
            onComplete={() => setTaglineComplete(true)}
          />
        </div>

        {/* CTA Button */}
        <button
          className={`${styles.ctaButton} ${taglineComplete ? styles.ctaVisible : ''}`}
          onClick={onEnterConsole}
          aria-label="Enter Console"
        >
          <span className={styles.buttonIcon}>&gt;</span>
          ENTER CONSOLE
        </button>

        {/* Features */}
        <div className={`${styles.features} ${taglineComplete ? styles.featuresVisible : ''}`}>
          {FEATURES.map((feature, index) => (
            <div key={index} className={styles.feature}>
              <span className={styles.featureIcon}>{feature.icon}</span>
              <span className={styles.featureLabel}>{feature.label}</span>
            </div>
          ))}
        </div>

        {/* Footer */}
        <footer className={styles.footer}>
          <div className={styles.footerLine}>
            ARCNET CONSOLE v1.0.0
          </div>
          <div className={styles.footerLine}>
            SECURE ACCESS TERMINAL
          </div>
        </footer>
      </div>
    </div>
  );
}

export default LandingPage;
