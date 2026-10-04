import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
  preview: {
    host: '0.0.0.0',
    proxy: {
      '/api': 'http://backend:8080',
      '/actuator': 'http://backend:8080',
    },
  },
})
