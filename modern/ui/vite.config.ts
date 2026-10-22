import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

const services = {
  licence: process.env.LICENCE_URL ?? 'http://localhost:8081',
  demerit: process.env.DEMERIT_URL ?? 'http://localhost:8082',
  registration: process.env.REGISTRATION_URL ?? 'http://localhost:8083',
  notification: process.env.NOTIFICATION_URL ?? 'http://localhost:8084',
}

const proxy: Record<string, ProxyOptions> = Object.fromEntries(
  Object.entries(services).map(([name, target]) => [
    `/${name}`,
    {
      target,
      changeOrigin: true,
      rewrite: (path: string) => path.replace(new RegExp(`^/${name}`), '') || '/',
    },
  ]),
)

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, host: true, proxy },
  preview: { port: 5173, strictPort: true, host: true, proxy },
})
