import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 相对 base：页面挂在飞牛统一网关 /app/czxs/admin/ 下，资源用相对路径
export default defineConfig({
  base: '/app/czxs/admin/',
  plugins: [react()],
  build: { outDir: 'dist', emptyOutDir: true },
})
