const { test, expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');
const { AxeBuilder } = require('@axe-core/playwright');

test.describe('Printable CRF', () => {
  test('should display investigator signature and labels', async ({ page }) => {
    page.on('console', (msg) => console.log('BROWSER LOG:', msg.text()));
    page.on('pageerror', (err) =>
      console.log('PAGE ERROR:', err.stack || err.message)
    );

    // Read Vite manifest to get the correct bundle file name
    const manifestPath = path.join(
      __dirname,
      '../../web/src/main/webapp/dist/.vite/manifest.json'
    );
    let mainScript = 'assets/main.js'; // fallback
    try {
      const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf-8'));
      mainScript = manifest['src/main/webapp/js/main.js'].file;
    } catch {
      // Fallback if manifest is missing
    }

    await page.route('**/clinicaldata/html/print/**', async (route) => {
      const mockHtml = `
      <html>
       <head>
        <meta charset="UTF-8">
        <title>OpenClinica - Printable Forms</title>
        <script>
          window.app_studyOID = 'STUDY-123';
          window.app_investigatorLabel = 'Investigator';
          window.app_investigatorSignatureLabel = 'Investigator Signature';
          window.app_meaning_of_signatureLabel = 'Meaning of Signature';
        </script>
        <script src="/OpenClinica-web/dist/${mainScript}"></script>
        <style>
          .spinner { display: none; }
          .sr-only {
            position: absolute;
            width: 1px;
            height: 1px;
            padding: 0;
            margin: -1px;
            overflow: hidden;
            clip: rect(0, 0, 0, 0);
            white-space: nowrap;
            border: 0;
          }
        </style>
       </head>
       <body>
         <div id="menuContainer"></div>
         <script>
           setTimeout(() => {
             document.dispatchEvent(new Event('DOMContentLoaded'));
           }, 1000);
         </script>
       </body>
      </html>
      `;
      route.fulfill({ contentType: 'text/html', body: mockHtml });
    });

    await page.route('**/dist/assets/*.js', async (route) => {
      const url = new URL(route.request().url());
      const fileName = path.basename(url.pathname);
      const assetsDir = path.join(
        __dirname,
        '../../web/src/main/webapp/dist/assets'
      );

      try {
        const bundle = fs.readFileSync(path.join(assetsDir, fileName), 'utf8');
        route.fulfill({ contentType: 'application/javascript', body: bundle });
      } catch {
        route.abort();
      }
    });

    await page.goto(
      '/OpenClinica-web/rest/clinicaldata/html/print/STUDY-123/SUBJ-1/EVENT-1/FORM-1'
    );

    // Wait for React to render the component
    await expect(page.locator('.crf-renderer')).toBeVisible({ timeout: 10000 });

    // The investigator signature must be visible
    const signatureBlock = page.locator('.investigator-signature');
    await expect(signatureBlock).toBeVisible();
    await expect(signatureBlock).toContainText('Investigator:');
    await expect(signatureBlock).toContainText('Investigator Signature:');
    await expect(signatureBlock).toContainText('Meaning of Signature:');

    const accessibilityScanResults = await new AxeBuilder({ page })
      .withTags(['wcag21a', 'wcag21aa'])
      .analyze();

    if (accessibilityScanResults.violations.length > 0) {
      console.log('Accessibility Violations:');
      accessibilityScanResults.violations.forEach((violation) => {
        console.log(`\nRule: ${violation.id} (${violation.impact})`);
        console.log(`Description: ${violation.description}`);
        console.log(`Help: ${violation.help}`);
        console.log(`Help URL: ${violation.helpUrl}`);
        violation.nodes.forEach((node) => {
          console.log(`- Element: ${node.html}`);
          console.log(`  Target: ${node.target.join(', ')}`);
          console.log(`  Failure Summary: ${node.failureSummary}`);
        });
      });
    } else {
      console.log('No accessibility violations found.');
    }
    expect(accessibilityScanResults.violations).toEqual([]);

    // Take a snapshot
    await expect(page).toHaveScreenshot('crf-printable-view.png', {
      maxDiffPixelRatio: 0.01,
    });
  });
});
