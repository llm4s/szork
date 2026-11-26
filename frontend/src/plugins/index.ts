import type { App } from "vue";
import vuetify from "./vuetify";
import router from "../router";

/** Installs the app's plugins: Vuetify and the router. */
export function registerPlugins(app: App) {
  app.use(vuetify).use(router);
}
