import type { ChartMarker } from "../components/TimelineChart";

/**
 * Chart markers from order-intent text like "ENTER:PE:2:CONFIRMED EXIT:PE:0:INVALIDATED" or
 * "ENTER_STRADDLE:CE+PE:500000:BREAKOUT_UP". {@code exits} false leaves exits to the caller (a
 * session's exits come from its positions, which include the executor's own stops and brackets).
 */
export function markersFromOrders(rows: { t: string; orders: string | null }[], exits = true): ChartMarker[] {
  const markers: ChartMarker[] = [];
  for (const row of rows) {
    if (!row.orders) continue;
    for (const order of row.orders.split(" ")) {
      const [action, side, lots, reason] = order.split(":");
      if (action === "EXIT") {
        if (exits) markers.push({ t: row.t, text: `exit ${side} ${reason ?? ""}`, tone: "info" });
      } else if (action === "ENTER_STRADDLE") {
        const budget = Number(lots);
        markers.push({ t: row.t, text: `buy straddle${budget > 0 ? ` ₹${(budget / 100000).toFixed(1)}L` : ""}`, tone: "up" });
      } else {
        markers.push({ t: row.t, text: `${action === "ADD" ? "add" : "buy"} ${side} ${lots}`, tone: side === "CE" ? "up" : "down" });
      }
    }
  }
  return markers;
}
