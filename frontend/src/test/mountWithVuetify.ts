import { mount, type ComponentMountingOptions } from "@vue/test-utils";
import { createVuetify } from "vuetify";
import type { Component } from "vue";

// jsdom lacks ResizeObserver, which some Vuetify components use
if (!("ResizeObserver" in globalThis)) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

/** Mount a component with Vuetify installed (the components themselves are auto-imported by the Vite plugin). */
export function mountWithVuetify<C extends Component>(component: C, options: ComponentMountingOptions<C> = {}) {
  return mount(component, {
    ...options,
    global: { ...options.global, plugins: [createVuetify(), ...(options.global?.plugins ?? [])] },
  });
}
