import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/v1": "http://127.0.0.1:58080",
      "/oauth2": "http://127.0.0.1:58080",
      "/login": "http://127.0.0.1:58080",
    },
  },
});
