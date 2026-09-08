import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    // 构建产物直接输出到后端 static 目录，由 Spring Boot 同源托管
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
})
