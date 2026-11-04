import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const gateway = process.env.DEMO_GATEWAY_URL ?? 'http://127.0.0.1:8080';
const target = new URL(gateway);
if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password
  || target.pathname !== '/' || target.search || target.hash) throw new Error('DEMO_GATEWAY_URL에 Gateway의 HTTP 주소를 지정하세요.');

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, proxy: {
    '/api': { target: gateway, changeOrigin: true },
    '/demo': 'http://127.0.0.1:4174',
  } },
});
