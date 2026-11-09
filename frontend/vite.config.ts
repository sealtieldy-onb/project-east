import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Bind only the IPv4 loopback interface; use the documented `localhost`
    // URL in the browser so it matches the Google OAuth host and cookies.
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.EAST_API_TARGET ?? "http://127.0.0.1:8080",
        // Match the backend's IPv4 loopback bind. changeOrigin keeps the proxy Host local while Origin stays the trusted browser origin.
        changeOrigin: true,
      },
    },
  },
  preview: {
    host: "127.0.0.1",
    proxy: {
      "/api": {
        target: process.env.EAST_API_TARGET ?? "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
});
