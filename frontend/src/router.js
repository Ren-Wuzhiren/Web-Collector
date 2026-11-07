import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const LoginView = () => import('./views/LoginView.vue')
const LinksView = () => import('./views/LinksView.vue')
const LinkDetailView = () => import('./views/LinkDetailView.vue')
const SearchView = () => import('./views/SearchView.vue')
const TagsView = () => import('./views/TagsView.vue')
const AdminView = () => import('./views/AdminView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/links' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/links', component: LinksView },
    { path: '/links/:id', component: LinkDetailView },
    { path: '/search', component: SearchView },
    { path: '/tags', component: TagsView },
    { path: '/admin', component: AdminView, meta: { requiresAdmin: true } },
  ],
})

router.beforeEach(async (to) => {
  const authenticated = Boolean(localStorage.getItem('webcollector-token'))
  if (!to.meta.public && !authenticated) {
    return '/login'
  }

  if (to.meta.requiresAdmin) {
    const auth = useAuthStore()
    let loadFailed = false
    try {
      await auth.loadCurrentUser()
    } catch (error) {
      if (error.status === 401) return '/login'
      loadFailed = true
    }
    if (!loadFailed && !auth.isAdmin) {
      return '/links'
    }
  }

  if (to.path === '/login' && authenticated) {
    return '/links'
  }
})

export default router
