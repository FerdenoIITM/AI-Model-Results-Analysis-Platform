import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Scaffold config — extend when implementing the dashboard.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
  },
});
