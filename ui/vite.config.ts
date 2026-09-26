import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Dev: `npm run dev` on 5173 proxies /api to trading-core (bin/trading-core --mode=serve|live).
// Prod: `npm run build` writes ui/dist, which trading-core serves on 127.0.0.1:8095.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: { "/api": "http://127.0.0.1:8095" },
  },
  build: { outDir: "dist", sourcemap: true },
});
