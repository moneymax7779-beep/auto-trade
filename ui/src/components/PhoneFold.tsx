import { type ReactNode, useEffect, useState } from "react";

const PHONE = "(max-width: 767px)";

/**
 * Mobile phase 3 (17 Nov 2026): on a phone a heavy panel (OI tape, PCR) is a one-line button and is not mounted (so it
 * fetches nothing) until tapped; from md up it shows as before.
 */
export function PhoneFold({ label, children }: { label: string; children: ReactNode }) {
  const [phone, setPhone] = useState(() => typeof window !== "undefined" && !!window.matchMedia?.(PHONE).matches);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const m = window.matchMedia?.(PHONE);
    if (!m) return;
    const on = () => setPhone(m.matches);
    m.addEventListener("change", on);
    return () => m.removeEventListener("change", on);
  }, []);
  if (!phone) return <>{children}</>;
  return (
    <div className="flex flex-col gap-3">
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}
        className="flex min-h-11 items-center justify-between rounded-lg border border-line bg-panel px-3 text-sm font-semibold">
        <span>{label}</span><span className="text-muted">{open ? "Hide" : "Show"}</span>
      </button>
      {open && children}
    </div>
  );
}
