import { type ReactNode, useEffect, useState } from "react";
import { NavLink, useLocation } from "react-router";

/** A menu entry: path and label. */
export type NavItem = readonly [string, string];

/** "/index/NIFTY" stands for every index chart: the chart tab stays lit on SENSEX and BANKNIFTY too. */
function matches(tab: string, path: string): boolean {
  const section = tab.startsWith("/index/") ? "/index/" : tab;
  return path === section || path.startsWith(section.endsWith("/") ? section : section + "/");
}

/**
 * Mobile phase 0 (17 Nov 2026): below the md breakpoint (768 px) the top menu is replaced by a bottom tab bar with
 * four tabs and More. More opens a sheet with every other menu item plus the workspace switcher and the sign-out, so
 * nothing the desktop menu offers is lost on a phone. Hidden from md up, where the top menu shows.
 */
export function MobileNav({ tabs, nav, extra }: { tabs: readonly NavItem[]; nav: readonly NavItem[]; extra?: ReactNode }) {
  const location = useLocation();
  const pathname = location.pathname === "/" && tabs.length > 0 ? tabs[0][0] : location.pathname;   // "/" is the first tab
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [location.pathname]);
  useEffect(() => {
    if (!open) return;
    const close = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [open]);
  const rest = nav.filter(([to]) => !tabs.some(([t]) => matches(t, to)) || (to.startsWith("/index/") && to !== "/index/NIFTY"));
  const moreActive = !tabs.some(([t]) => matches(t, pathname));
  const tab = (active: boolean) =>
    // sky-700 on white is 5.9:1; the accent blue (#60a5fa) is 2.5:1, too pale for a label (review 17 Nov)
    `flex min-h-12 flex-1 flex-col items-center justify-center gap-0.5 border-t-2 text-xs ${active
      ? "border-sky-700 font-semibold text-sky-700" : "border-transparent text-muted"}`;
  return (
    <div className="md:hidden">
      {open && (
        <div className="fixed inset-0 z-30 bg-black/30" onClick={() => setOpen(false)} aria-hidden="true" />
      )}
      {open && (
        <div role="dialog" aria-modal="true" aria-label="More"
          className="fixed inset-x-0 bottom-0 z-40 max-h-[80vh] overflow-y-auto rounded-t-2xl border-t border-line bg-panel pb-[calc(4.5rem+env(safe-area-inset-bottom))] pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))] pt-3 shadow-2xl">
          <div className="mx-auto mb-2 h-1 w-10 rounded bg-line" />
          <nav className="grid grid-cols-2 gap-2">
            {rest.map(([to, label], i) => (
              <NavLink key={to} to={to} autoFocus={i === 0}
                className={({ isActive }) => `flex min-h-11 items-center rounded-lg border px-3 text-sm ${isActive ? "border-sky-700 font-semibold text-sky-700" : "border-line"}`}>
                {label}
              </NavLink>
            ))}
          </nav>
          {extra && <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-3">{extra}</div>}
        </div>
      )}
      <nav aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-40 flex border-t border-line bg-panel pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        {tabs.map(([to, label]) => (
          <NavLink key={to} to={to} className={() => tab(matches(to, pathname))}>
            {label}
          </NavLink>
        ))}
        <button type="button" className={tab(open || moreActive)} aria-expanded={open} onClick={() => setOpen(!open)}>
          More
        </button>
      </nav>
    </div>
  );
}
