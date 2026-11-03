import { defineStore } from 'pinia'
import api from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('webcollector-token'),
    user: null,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token),
  },
  actions: {
    async login(username, password) {
      const response = await api.post('/auth/login', { username, password })
      this.token = response.data.token
      this.user = response.data.user
      localStorage.setItem('webcollector-token', this.token)
    },
    async register(username, email, password) {
      await api.post('/auth/register', { username, email, password })
    },
    async loadCurrentUser() {
      if (!this.token) return
      const response = await api.get('/auth/me')
      this.user = response.data
    },
    async logout() {
      try {
        await api.post('/auth/logout')
      } finally {
        this.token = null
        this.user = null
        localStorage.removeItem('webcollector-token')
      }
    },
  },
})
