import 'prototype-js-core';
import 'scriptaculous-js';
import './setup-globals.js';

import 'headjs/dist/1.0.0/head.min.js';
import '@phagento/jquery-tmpl';
import 'jquery-migrate';
import 'jquery-blockui';
import './blockui-a11y.js';

// Initialize the reactive store
import { store } from './store.js';

import 'json3';
import './vendor/jmesa/index.js';
// eslint-disable-next-line no-restricted-imports
import './vendor/new_cal/index.js';
import './vendor/wz_tooltip/wz_tooltip.js';
// eslint-disable-next-line no-restricted-imports
import './vendor/calendarpopup/CalendarPopup.js';

// Initialize modern React UI (dynamically imported below)

async function mountReactApp() {
  const isPrintableMode =
    (window.app_studyOID !== undefined &&
      document.title.includes('Printable Forms')) ||
    window.location.pathname.includes('printcrf');

  const crfContainer = document.getElementById('printCRFContainer');
  const menuContainer = document.getElementById('menuContainer');

  const needsCRF = !!(isPrintableMode && crfContainer);
  // Navigation is bypassed if we are in printable mode to fix memory leaks
  const needsNavigation = !isPrintableMode && !!menuContainer;

  if (!needsNavigation && !needsCRF) {
    return;
  }

  const [
    { default: React },
    { createRoot },
    { AccessibilityProvider },
    { default: ErrorBoundary },
  ] = await Promise.all([
    import('react'),
    import('react-dom/client'),
    import('./components/AccessibilityProvider.jsx'),
    import('./components/ErrorBoundary.jsx'),
  ]);

  if (needsCRF) {
    // If this is the print CRF page, replace the body or specific element with the new renderer
    const CRFRenderer = React.lazy(
      () => import('./components/CRFRenderer.jsx')
    );
    const crfRoot = createRoot(crfContainer);
    crfRoot.render(
      React.createElement(
        ErrorBoundary,
        null,
        React.createElement(
          AccessibilityProvider,
          null,
          React.createElement(
            React.Suspense,
            { fallback: React.createElement('div', null, 'Loading CRF...') },
            React.createElement(CRFRenderer, null)
          )
        )
      )
    );
  } else if (needsNavigation) {
    // Mount the modern navigation menu if the container exists
    const Navigation = React.lazy(() => import('./components/Navigation.jsx'));
    const navRoot = createRoot(menuContainer);
    navRoot.render(
      React.createElement(
        ErrorBoundary,
        null,
        React.createElement(
          AccessibilityProvider,
          null,
          React.createElement(
            React.Suspense,
            {
              fallback: React.createElement(
                'div',
                null,
                'Loading Navigation...'
              ),
            },
            React.createElement(Navigation, null)
          )
        )
      )
    );
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    mountReactApp();
    clearHardcodedTabIndex();
  });
} else {
  mountReactApp();
  clearHardcodedTabIndex();
}

function clearHardcodedTabIndex() {
  const formElements = document.querySelectorAll(
    'input[tabindex], select[tabindex], textarea[tabindex], button[tabindex]'
  );
  formElements.forEach((el) => {
    const tabIndex = parseInt(el.getAttribute('tabindex'), 10);
    // Only clear if it's explicitly set to break visual flow (positive tab index)
    if (tabIndex > 0) {
      el.removeAttribute('tabindex');
    }
  });
}
