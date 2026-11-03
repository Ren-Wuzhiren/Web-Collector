import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import LinksView from './views/LinksView.vue'
import SearchView from './views/SearchView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/links' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/links', component: LinksView },
    { path: '/search', component: SearchView },
  ],
})

router.beforeEach((to) => {
  const authenticated = Boolean(localStorage.getItem('webcollector-token'))
  if (!to.meta.public && !authenticated) {
    return '/login'
  }
  if (to.path === '/login' && authenticated) {
    return '/links'
  }
})

export default router
