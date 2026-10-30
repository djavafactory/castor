import js from '@eslint/js'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import globals from 'globals'
import tseslint from 'typescript-eslint'

const FEATURES = [
  'auth',
  'shell',
  'scene',
  'monitor',
  'designer',
  'palette',
  'inspector',
  'versions',
  'catalogue',
  'details',
  'integrations',
]

const HEX_COLOUR = '#[0-9a-fA-F]{3,8}\\b'

// A feature may not import another feature; shared code lives in src/lib and src/components.
const crossFeatureRules = FEATURES.map((feature) => ({
  files: [`src/features/${feature}/**/*.{ts,tsx}`],
  rules: {
    'no-restricted-imports': [
      'error',
      {
        patterns: FEATURES.filter((other) => other !== feature).map((other) => ({
          regex: `^(@/features|(\\.\\./)+)/?${other}(/|$)`,
          message:
            'Features must not import from each other. Move shared code to src/lib or src/components.',
        })),
      },
    ],
  },
}))

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results', 'src/types/api.ts'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Colours come from the design tokens (SETUP-06, CORE-03), never from literals.
      'no-restricted-syntax': [
        'error',
        {
          selector: `Literal[value=/${HEX_COLOUR}/]`,
          message: 'Hard-coded hex colours are only allowed in the token files.',
        },
        {
          selector: `TemplateElement[value.raw=/${HEX_COLOUR}/]`,
          message: 'Hard-coded hex colours are only allowed in the token files.',
        },
      ],
    },
  },
  ...crossFeatureRules,
  {
    // Token files may define raw colours; so may the API payload fixtures, where a colour is data from the server.
    files: [
      'src/styles/tokens/**/*.{ts,tsx}',
      'src/lib/tokens/**/*.{ts,tsx}',
      'src/features/scene/palette.ts',
      'src/features/scene/palette.test.ts',
      'src/test/fixtures.ts',
    ],
    rules: { 'no-restricted-syntax': 'off' },
  },
  {
    files: ['**/*.test.{ts,tsx}', 'src/test/**/*.{ts,tsx}'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
  {
    files: ['*.config.{js,ts}', 'scripts/**/*.mjs', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
)
