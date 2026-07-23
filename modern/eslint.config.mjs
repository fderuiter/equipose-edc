import js from '@eslint/js';
import reactPlugin from 'eslint-plugin-react';
import jsxA11yPlugin from 'eslint-plugin-jsx-a11y';
import globals from 'globals';

export default [
  js.configs.recommended,
  {
    ignores: ['node_modules/**', '**/target/**', '**/dist/**'],
  },
  {
    files: ['**/*.js', '**/*.jsx'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      parserOptions: {
        ecmaFeatures: {
          jsx: true,
        },
      },
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    plugins: {
      react: reactPlugin,
      'jsx-a11y': jsxA11yPlugin,
    },
    rules: {
      ...reactPlugin.configs.recommended.rules,
      ...jsxA11yPlugin.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react/prop-types': 'off',
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '**/new_cal/**',
                '**/calendarpopup/**',
                '**/*calendar*',
                '**/jquery.js',
                '**/jquery-*.js',
                '**/jquery.*.js',
              ],
              message: 'Use of legacy calendar assets or unapproved jQuery variants is restricted.',
            },
          ],
        },
      ],
    },
    settings: {
      react: {
        version: '18.3.1',
      },
    },
  },
];
