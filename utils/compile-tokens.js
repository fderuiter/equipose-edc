const fs = require('fs');
const path = require('path');

const tokensPath = path.join(__dirname, 'tokens.json');
const tokens = JSON.parse(fs.readFileSync(tokensPath, 'utf8'));

// 1. Compile Raw CSS Variables (for legacy shared stylesheet)
const lightColors = tokens.themes.light.colors;
const darkColors = tokens.themes.dark.colors;
const spacing = tokens.spacing;
const typography = tokens.typography;

let cssContent = `/* Automatically generated from tokens.json - DO NOT EDIT MANUALLY */\n\n`;

const mapColorKey = (key) => {
  if (key === 'background') return 'bg-color';
  return `${key}-color`;
};

// Light theme variables (default root)
cssContent += `:root {\n`;
for (const [key, value] of Object.entries(lightColors)) {
  cssContent += `  --${mapColorKey(key)}: ${value};\n`;
}
cssContent += `\n`;
for (const [key, value] of Object.entries(typography)) {
  cssContent += `  --font-${key}: ${value};\n`;
}
cssContent += `\n`;
for (const [key, value] of Object.entries(spacing)) {
  cssContent += `  --spacing-${key}: ${value};\n`;
}
cssContent += `  --fluid-width: 100%;\n`;
cssContent += `}\n\n`;

// Dark theme variables
cssContent += `@media (prefers-color-scheme: dark) {\n`;
cssContent += `  :root {\n`;
for (const [key, value] of Object.entries(darkColors)) {
  cssContent += `    --${mapColorKey(key)}: ${value};\n`;
}
cssContent += `  }\n`;
cssContent += `}\n`;

const sharedThemeCssPath = path.join(
  __dirname,
  '../web/src/main/webapp/css/shared-theme.css'
);
fs.writeFileSync(sharedThemeCssPath, cssContent);
console.log(`Successfully compiled CSS variables to ${sharedThemeCssPath}`);

// 2. Compile Vuetify Configuration
const vuetifyTheme = {
  theme: {
    defaultTheme: 'light',
    themes: {
      light: {
        dark: false,
        colors: {
          primary: lightColors.primary,
          background: lightColors.background,
          surface: lightColors.background,
          error: lightColors.error,
          info: lightColors.focus,
          success: lightColors.primary,
          warning: lightColors.highlight,
        },
        variables: {
          'spacing-sm': spacing.sm,
          'spacing-md': spacing.md,
          'spacing-lg': spacing.lg,
          'spacing-xl': spacing.xl,
        },
      },
      dark: {
        dark: true,
        colors: {
          primary: darkColors.primary,
          background: darkColors.background,
          surface: darkColors.background,
          error: darkColors.error,
          info: darkColors.focus,
          success: darkColors.primary,
          warning: darkColors.highlight,
        },
        variables: {
          'spacing-sm': spacing.sm,
          'spacing-md': spacing.md,
          'spacing-lg': spacing.lg,
          'spacing-xl': spacing.xl,
        },
      },
    },
  },
};

const vuetifyOutputPath = path.join(__dirname, 'vuetify-theme.json');
fs.writeFileSync(
  vuetifyOutputPath,
  JSON.stringify(vuetifyTheme, null, 2) + '\n'
);
console.log(`Successfully compiled Vuetify config to ${vuetifyOutputPath}`);

// Copy also to web css folder for access
fs.writeFileSync(
  path.join(__dirname, '../web/src/main/webapp/css/vuetify-theme.json'),
  JSON.stringify(vuetifyTheme, null, 2) + '\n'
);

// 3. Compile Tailwind Configuration
const tailwindTheme = {
  theme: {
    extend: {
      colors: {
        primary: {
          light: lightColors.primary,
          dark: darkColors.primary,
        },
        text: {
          light: lightColors.text,
          dark: darkColors.text,
        },
        background: {
          light: lightColors.background,
          dark: darkColors.background,
        },
        border: {
          light: lightColors.border,
          dark: darkColors.border,
        },
        focus: {
          light: lightColors.focus,
          dark: darkColors.focus,
        },
        highlight: {
          light: lightColors.highlight,
          dark: darkColors.highlight,
        },
        error: {
          light: lightColors.error,
          dark: darkColors.error,
        },
      },
      spacing: {
        sm: spacing.sm,
        md: spacing.md,
        lg: spacing.lg,
        xl: spacing.xl,
      },
    },
  },
};

const tailwindOutputPath = path.join(__dirname, 'tailwind-theme.json');
fs.writeFileSync(
  tailwindOutputPath,
  JSON.stringify(tailwindTheme, null, 2) + '\n'
);
console.log(`Successfully compiled Tailwind config to ${tailwindOutputPath}`);

// Copy also to web css folder for access
fs.writeFileSync(
  path.join(__dirname, '../web/src/main/webapp/css/tailwind-theme.json'),
  JSON.stringify(tailwindTheme, null, 2) + '\n'
);
