import globals from "globals";
import pluginJs from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";
import prettier from "eslint-plugin-prettier";
import prettierConfig from "eslint-config-prettier";

export default [
  { files: ["**/*.{js,mjs,cjs,ts,vue}"] },
  {
    ignores: ["dist/*", "node_modules/*", "*.config.js", "*.config.ts", "*.config.mjs", "*.config.mts"],
  },
  { languageOptions: { globals: globals.browser } },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs["flat/essential"],
  {
    files: ["**/*.vue"],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    plugins: {
      prettier,
    },
    rules: {
      ...prettierConfig.rules,
      "prettier/prettier": "warn",
      "@typescript-eslint/no-unused-vars": "warn",
      "@typescript-eslint/no-explicit-any": "warn",
      "vue/multi-word-component-names": "off",
    },
  },
];