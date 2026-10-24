import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
import "./index.css";

// Per-user logins (autotrade.auth.mode=oidc): an API call after the session ended gets 401; start the Keycloak sign-in
// again (once) instead of showing errors. With mode none the API never answers 401, so this does nothing.
const nativeFetch = window.fetch.bind(window);
let signingIn = false;
window.fetch = async (input, init) => {
  const response = await nativeFetch(input, init);
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
  if (response.status === 401 && !signingIn && new URL(url, window.location.href).pathname.startsWith("/api/")) {
    signingIn = true;
    window.location.assign("/oauth2/authorization/keycloak");
  }
  return response;
};

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 5_000, retry: 1 } } });

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
