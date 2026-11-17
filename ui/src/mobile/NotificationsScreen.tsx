import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { get, postJson } from "../api";
import { Big, Card, ErrorLine } from "./common";

/** GET /api/execution/push: whether the server sends, its public key, and the caller's own devices. */
export interface PushState {
  enabled: boolean;
  publicKey: string | null;
  devices: { id: number; userAgent: string | null; createdAt: string; lastOk: string | null; lastError: string | null;
    endpoint: string }[];
}

const ios = () => /iPhone|iPad|iPod/.test(navigator.userAgent);
const installed = () => window.matchMedia?.("(display-mode: standalone)").matches
  || (navigator as Navigator & { standalone?: boolean }).standalone === true;
export const pushSupported = () => "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;

function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const b64 = (base64url + "=".repeat((4 - (base64url.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/** This browser's own subscription (null: none, or no service worker yet). */
async function current(): Promise<PushSubscription | null> {
  if (!pushSupported()) return null;
  const reg = await navigator.serviceWorker.getRegistration();
  return reg ? reg.pushManager.getSubscription() : null;
}

export function usePush() {
  const state = useQuery({ queryKey: ["push"], queryFn: () => get<PushState>("/api/execution/push"), retry: false,
    staleTime: 30_000 });
  const [here, setHere] = useState<PushSubscription | null | undefined>(undefined);
  useEffect(() => { current().then(setHere, () => setHere(null)); }, [state.dataUpdatedAt]);
  const permission = "Notification" in window ? Notification.permission : "unsupported";
  return { state, here, permission };
}

/**
 * Mobile phase 4 (17 Nov 2026): notifications on this phone. The same alerts as Telegram (login reminder, LIVE entries
 * and exits, stops, kill switches, broker disconnects) to every device a person turned on; Telegram stays the second
 * channel. On an iPhone, web push works only once the app is on the Home Screen.
 */
export function NotificationsScreen() {
  const client = useQueryClient();
  const { state, here, permission } = usePush();
  const [note, setNote] = useState<string | null>(null);
  const refresh = () => client.invalidateQueries({ queryKey: ["push"] });
  const on = useMutation({
    mutationFn: async () => {
      if (!state.data?.publicKey) throw new Error("notifications are not switched on for this server yet");
      if ((await Notification.requestPermission()) !== "granted") {
        throw new Error("Notifications are blocked for this site. Allow them in the phone's settings for this app, then try again.");
      }
      const reg = await navigator.serviceWorker.ready;
      const sub = (await reg.pushManager.getSubscription()) ?? await reg.pushManager.subscribe({ userVisibleOnly: true,
        applicationServerKey: keyBytes(state.data.publicKey) });
      const json = sub.toJSON();
      await postJson("/api/execution/push/subscribe", { endpoint: sub.endpoint, keys: json.keys,
        userAgent: navigator.userAgent });
      return "Notifications are on for this phone.";
    },
    onSuccess: setNote, onSettled: refresh,
  });
  const off = useMutation({
    mutationFn: async () => {
      const sub = await current();
      if (sub) {
        await postJson("/api/execution/push/unsubscribe", { endpoint: sub.endpoint });
        await sub.unsubscribe();
      }
      return "Notifications are off for this phone.";
    },
    onSuccess: setNote, onSettled: refresh,
  });
  const test = useMutation({
    mutationFn: () => postJson<{ devices: number; delivered: number }>("/api/execution/push/test", {}),
    onSuccess: (r) => setNote(`Test sent: ${r.delivered} of ${r.devices} device${r.devices === 1 ? "" : "s"} accepted it.`),
  });
  const remove = useMutation({
    mutationFn: (id: number) => postJson("/api/execution/push/unsubscribe", { id }),
    onSettled: refresh,
  });
  const s = state.data;
  const blocked = permission === "denied";
  const needsInstall = ios() && !installed();
  const busy = on.isPending || off.isPending;
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-3">
      <h1 className="text-xl font-semibold">Notifications</h1>
      <ErrorLine error={state.error} />
      <Card>
        <p className="mb-2 text-sm">The same alerts as Telegram: the 08:30 login reminder, LIVE entries and exits, stops, kill
          switches and broker disconnects. Telegram keeps working as well.</p>
        {s && !s.enabled ? (
          <p className="text-sm font-semibold text-warn">Notifications are not switched on for this server yet (the owner turns
            them on).</p>
        ) : !pushSupported() ? (
          <p className="text-sm text-warn">This browser cannot receive notifications.</p>
        ) : needsInstall ? (
          <div className="flex flex-col gap-2 text-sm">
            <p className="font-semibold">On iPhone: add auto-trade to the Home Screen first.</p>
            <ol className="list-decimal pl-5"><li>Tap the Share button in Safari.</li><li>Add to Home Screen.</li>
              <li>Open auto-trade from the Home Screen and come back here.</li></ol>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            <p className="text-sm">This phone: <b>{here ? "on" : blocked ? "blocked" : "off"}</b></p>
            {blocked && <p className="text-xs text-warn">Notifications are blocked for this site in the phone's settings.</p>}
            {here ? (
              <div className="grid grid-cols-2 gap-2">
                <Big disabled={test.isPending} onClick={() => test.mutate()}>Send a test</Big>
                <Big disabled={busy} onClick={() => off.mutate()}>Turn off here</Big>
              </div>
            ) : (
              <Big kind="primary" disabled={busy || blocked || !s} onClick={() => on.mutate()}>
                {on.isPending ? "Turning on…" : "Turn on for this phone"}</Big>
            )}
          </div>
        )}
        {note && <p className="mt-2 text-sm text-up">{note}</p>}
        {(on.error || off.error || test.error) && <p className="mt-2 text-sm text-down">
          {((on.error ?? off.error ?? test.error) as Error).message}</p>}
      </Card>
      {s && s.devices.length > 0 && (
        <Card>
          <h2 className="mb-2 text-sm font-semibold">Your devices</h2>
          <ul className="flex flex-col divide-y divide-line text-sm">
            {s.devices.map((d) => (
              <li key={d.id} className="flex items-center justify-between gap-2 py-2">
                <span className="min-w-0"><span className="block truncate">{device(d.userAgent)}</span>
                  <span className="text-xs text-muted">{d.lastError ? `last try failed (${d.lastError})`
                    : d.lastOk ? `last alert ${d.lastOk.slice(0, 16).replace("T", " ")}` : `added ${d.createdAt.slice(0, 10)}`}</span></span>
                <button type="button" className="min-h-11 shrink-0 rounded-lg border border-line px-3 text-sm"
                  disabled={remove.isPending} onClick={() => remove.mutate(d.id)}>Remove</button>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}

function device(ua: string | null): string {
  if (!ua) return "A browser";
  const os = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : /Mac/.test(ua) ? "Mac"
    : /Windows/.test(ua) ? "Windows" : "Device";
  const app = /CriOS|Chrome/.test(ua) && !/Edg/.test(ua) ? "Chrome" : /Edg/.test(ua) ? "Edge" : /Firefox|FxiOS/.test(ua) ? "Firefox"
    : /Safari/.test(ua) ? "Safari" : "browser";
  return `${os} · ${app}`;
}
