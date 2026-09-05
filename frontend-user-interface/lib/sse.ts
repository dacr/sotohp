// Live-update bus: a single SSE connection to GET /api/system/events (see EventBusService /
// eventsEndpoint in ApiApp.scala) tells every connected client about mutations as they happen.
//
// Native EventSource can't set an Authorization header, so the token travels as a `?token=`
// query param — the backend already accepts that as a fallback (SecureEndpoints.bearerAuth).
// EventSource auto-reconnects on its own, but always to the exact URL it was constructed with —
// so a stale token would never get refreshed. We instead close and reopen by hand on every
// `error`, rebuilding the URL (and its token) from scratch each time.

export type ApiEventAction = "created" | "updated" | "deleted";

export interface ApiEvent {
  entity: string;
  id?: string | null;
  action: ApiEventAction;
}

const RECONNECT_DELAY_MS = 3000;

export function connectEvents(getToken: () => string | null | undefined, onEvent: (event: ApiEvent) => void): () => void {
  let es: EventSource | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;

  function open() {
    if (stopped) return;
    const token = getToken();
    const url = token ? `/api/system/events?token=${encodeURIComponent(token)}` : "/api/system/events";
    es = new EventSource(url);
    es.onmessage = (ev) => {
      try {
        onEvent(JSON.parse(ev.data) as ApiEvent);
      } catch {
        /* malformed frame, ignore */
      }
    };
    es.onerror = () => {
      es?.close();
      es = null;
      if (!stopped) reconnectTimer = setTimeout(open, RECONNECT_DELAY_MS);
    };
  }

  open();

  return () => {
    stopped = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    es?.close();
  };
}
