import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

/**
 * Dashboard entrypoint (scaffold).
 * Routing, auth providers, and pages will be wired in later milestones.
 */
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <main>
      <h1>AMRAP</h1>
      <p>AI Model Results Analysis Platform — frontend scaffold</p>
    </main>
  </StrictMode>
);
