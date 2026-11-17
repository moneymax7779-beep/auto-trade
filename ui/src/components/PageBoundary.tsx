import { Component, type ErrorInfo, type ReactNode } from "react";
import { reportError } from "../errors";

/**
 * Mobile phase 4 (17 Nov 2026): a page that fails to load or render (offline and its code not cached, a deploy in
 * between, a bug) shows a message with Try again, and the header and tab bar stay, instead of React removing the whole
 * app. Reset when the address changes (the `key` from the router).
 */
export class PageBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    if (typeof navigator === "undefined" || navigator.onLine !== false) {          // offline is not a bug to report
      reportError("page", Object.assign(new Error(error.message), { stack: `${error.stack ?? ""}\n${info.componentStack ?? ""}` }));
    }
  }

  render() {
    if (!this.state.error) return this.props.children;
    const offline = typeof navigator !== "undefined" && navigator.onLine === false;
    return (
      <div role="alert" className="mx-auto mt-6 flex max-w-md flex-col gap-3 rounded-xl border border-line bg-panel p-4 text-sm">
        <p className="font-semibold">{offline ? "You are offline: this page needs the network to open." : "This page could not open."}</p>
        {!offline && <p className="text-muted">{this.state.error.message}</p>}
        <button type="button" className="min-h-11 rounded-lg bg-sky-700 font-semibold text-white"
          onClick={() => (offline ? this.setState({ error: null }) : window.location.reload())}>Try again</button>
      </div>
    );
  }
}
