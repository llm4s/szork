import { createRouter, createWebHistory } from "vue-router";
import GameView from "@/layouts/GameView.vue";

const routes = [
  {
    path: "/",
    name: "Home",
    component: GameView,
  },
  {
    path: "/game/:gameId",
    name: "Game",
    component: GameView,
    props: true,
  },
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
});

export default router;