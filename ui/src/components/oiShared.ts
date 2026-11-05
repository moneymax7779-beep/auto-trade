import { useEffect, useRef, useState, type RefObject } from "react";

/** Shared by the OI tape and its strike ladder. */
export const CALL = "var(--color-accent)", PUT = "var(--color-warn)";

/** OI and volume in short units: "1.2M", "−32k", "+840" ({@code signed}: a leading + on gains). */
export const big = (v: number | null | undefined, signed = false) => {
  if (v == null || !Number.isFinite(v)) return "–";
  const a = Math.abs(v), s = signed ? (v > 0 ? "+" : v < 0 ? "−" : "") : v < 0 ? "−" : "";
  return a >= 1e6 ? `${s}${(a / 1e6).toFixed(a >= 1e8 ? 0 : 1)}M` : a >= 1e3 ? `${s}${(a / 1e3).toFixed(0)}k` : `${s}${a.toFixed(0)}`;
};

export const px = (v: number | null | undefined) => (v == null ? "–" : v.toLocaleString("en-IN", { maximumFractionDigits: 2 }));

/** An element's width, followed as it resizes (800 until measured). */
export function useWidth<T extends HTMLElement>(): [RefObject<T | null>, number] {
  const ref = useRef<T>(null);
  const [w, setW] = useState(800);
  useEffect(() => {
    const el = ref.current; if (!el) return;
    const ro = new ResizeObserver(() => setW(el.clientWidth || 800)); ro.observe(el); setW(el.clientWidth || 800);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}
