import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createAuthService } from "./auth/createAuth";
import "./index.css";

const container = document.getElementById("root");
if (!container) throw new Error("index.html is missing #root");
const root = createRoot(container);
createAuthService().then(
  (service) =>
    root.render(
      <StrictMode>
        <App service={service} />
      </StrictMode>,
    ),
  (error: unknown) => {
    console.error(error);
    document.getElementById("boot-error")?.removeAttribute("hidden");
  },
);
