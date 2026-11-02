import { useEffect, useState } from "react";
export function useLive(tenant: string) {
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!tenant) return;
    let stopped = false,
      timer: ReturnType<typeof setTimeout>,
      socket: WebSocket;
    function connect() {
      socket = new WebSocket(
        `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/live?tenant=${encodeURIComponent(tenant)}`,
      );
      socket.onopen = () => setRevision((v) => v + 1);
      socket.onmessage = () => setRevision((v) => v + 1);
      socket.onclose = () => {
        if (!stopped) timer = setTimeout(connect, 5000);
      };
    }
    connect();
    return () => {
      stopped = true;
      clearTimeout(timer);
      socket?.close();
    };
  }, [tenant]);
  return revision;
}
