import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import CLIDemo from './pages/CLIDemo.tsx'

// Simple routing based on URL hash
function Router() {
  const hash = window.location.hash;

  if (hash === '#cli-demo') {
    return <CLIDemo />;
  }

  return <App />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Router />
  </StrictMode>,
)

// Listen for hash changes
window.addEventListener('hashchange', () => {
  window.location.reload();
});
