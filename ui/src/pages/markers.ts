import type { ChartMarker } from "../components/TimelineChart";

/** Chart markers from order-intent text like "ENTER:PE:2:CONFIRMED EXIT:PE:0:INVALIDATED". */
export function markersFromOrders(rows: { t: string; orders: string | null }[]): ChartMarker[] {
  const markers: ChartMarker[] = [];
  for (const row of rows) {
    if (!row.orders) continue;
    for (const order of row.orders.split(" ")) {
      const [action, side, lots, reason] = order.split(":");
      if (action === "EXIT") markers.push({ t: row.t, text: `exit ${side} ${reason ?? ""}`, tone: "info" });
      else markers.push({ t: row.t, text: `${action === "ADD" ? "add" : "buy"} ${side} ${lots}`, tone: side === "CE" ? "up" : "down" });
    }
  }
  return markers;
}
