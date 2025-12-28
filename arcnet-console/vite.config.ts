import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  // Optimize dependencies for deck.gl
  optimizeDeps: {
    include: [
      '@deck.gl/core',
      '@deck.gl/layers',
      '@deck.gl/geo-layers',
      '@deck.gl/react',
      'maplibre-gl',
    ],
  },

  // Build optimizations
  build: {
    // Increase chunk size warning limit for deck.gl
    chunkSizeWarningLimit: 1000,

    rollupOptions: {
      output: {
        // Manual chunk splitting for better caching
        manualChunks: (id) => {
          // deck.gl in its own chunk
          if (id.includes('@deck.gl')) {
            return 'deck-gl';
          }
          // maplibre in its own chunk
          if (id.includes('maplibre-gl')) {
            return 'maplibre';
          }
          // Charts in separate chunk
          if (id.includes('recharts') || id.includes('d3-')) {
            return 'charts';
          }
          // Vendor chunk for React and state management
          if (id.includes('react') || id.includes('zustand') || id.includes('immer')) {
            return 'vendor';
          }
        },
      },
    },

    // Enable source maps for debugging
    sourcemap: true,

    // Target modern browsers for better performance
    target: 'esnext',
  },

  // Development server configuration
  server: {
    port: 3000,
    open: true,
    // Allow connections from any host (useful for Docker/VM development)
    host: true,
  },

  // Preview server configuration
  preview: {
    port: 3001,
  },

  // Resolve configuration
  resolve: {
    alias: {
      // Add aliases for cleaner imports
      '@': '/src',
      '@components': '/src/components',
      '@hooks': '/src/hooks',
      '@stores': '/src/stores',
      '@types': '/src/types',
      '@layouts': '/src/layouts',
    },
  },

  // Define global constants
  define: {
    // Suppress maplibre-gl warnings in development
    'process.env.NODE_ENV': JSON.stringify(process.env.NODE_ENV || 'development'),
  },

  // CSS configuration
  css: {
    modules: {
      // Use camelCase for CSS module class names
      localsConvention: 'camelCase',
    },
  },
})
