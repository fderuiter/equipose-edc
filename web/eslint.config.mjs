import js from '@eslint/js';
import reactPlugin from 'eslint-plugin-react';
import jsxA11yPlugin from 'eslint-plugin-jsx-a11y';
import globals from 'globals';

export default [
  js.configs.recommended,
  {
    ignores: [
      'node_modules/**',
      '**/target/**',
      '**/dist/**',
      'src/main/webapp/js/vendor/**',
      'src/main/webapp/includes/**',
    ],
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
        ...globals.jest,
        app_contextPath: 'readonly',
        app_logLevel: 'readonly',
        idleTime: 'writable',
        DO_AUTO_LOGOUT: 'writable',
        DO_AUTO_SERVER_LOGOUT: 'writable',
        user: 'readonly',
        util_logout: 'readonly',
        $: 'readonly',
        head: 'readonly',
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
      'no-unused-vars': 'off',
      'no-undef': 'off',
      'no-empty': 'off',
      'no-useless-escape': 'off',
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
        version: 'detect',
      },
    },
  },
];
