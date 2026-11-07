import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.BACKEND_ORIGIN || 'http://localhost:8080',
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': process.env.BACKEND_ORIGIN || 'http://localhost:8080',
    },
  },
})
