import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
import "./index.css";

// Per-user logins (autotrade.auth.mode=oidc): when the sign-in has ended, trading-core answers an API call with 401 and
// X-Autotrade-Sign-In. Reload the page: the page request goes to the Keycloak sign-in and comes back to this page.
// Only that 401 (not one from behind the sign-in), and at most once a minute, so a refusal can never loop. With mode
// none the API never sends it, so this does nothing.
const nativeFetch = window.fetch.bind(window);
let signingIn = false;
window.fetch = async (input, init) => {
  const response = await nativeFetch(input, init);
  if (response.status === 401 && response.headers.get("X-Autotrade-Sign-In") && !signingIn) {
    signingIn = true;
    let last = 0;
    try {
      last = Number(sessionStorage.getItem("autotrade.signInReload") ?? 0);
      sessionStorage.setItem("autotrade.signInReload", String(Date.now()));
    } catch {
      last = 0;
    }
    if (Date.now() - last > 60_000) window.location.reload();
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
