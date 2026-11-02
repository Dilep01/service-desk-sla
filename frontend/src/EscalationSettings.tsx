import { useEffect, useState } from "react";
type Api = (path: string, method?: string, body?: unknown) => Promise<any>;
export function EscalationSettings({
  tenant,
  api,
}: {
  tenant: string;
  api: Api;
}) {
  const [minutes, setMinutes] = useState(30),
    [leads, setLeads] = useState(true),
    [message, setMessage] = useState("");
  useEffect(() => {
    let active = true;
    api(`/tenants/${tenant}/escalation`)
      .then((v) => {
        if (active) {
          setMinutes(v.warning_minutes);
          setLeads(v.escalate_to_leads);
        }
      })
      .catch((e) => {
        if (active) setMessage(e.message);
      });
    return () => {
      active = false;
    };
  }, [tenant]);
  return (
    <section className="panel operations">
      <h2>Escalation settings</h2>
      <p>
        Warn the assignee before the SLA deadline. Breaches always notify the
        assignee or requester; optionally include the team leads and
        organization administrators.
      </p>
      {message && <p role="status">{message}</p>}
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            await api(`/tenants/${tenant}/escalation`, "PUT", {
              warningMinutes: minutes,
              escalateToLeads: leads,
            });
            setMessage("Escalation settings saved.");
          } catch (e) {
            setMessage((e as Error).message);
          }
        }}
      >
        <label>
          Elapsed minutes before deadline
          <input
            type="number"
            min={1}
            max={1440}
            required
            value={minutes}
            onChange={(e) => setMinutes(Number(e.target.value))}
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={leads}
            onChange={(e) => setLeads(e.target.checked)}
          />
          Include leads and administrators on breaches
        </label>
        <button className="primary">Save escalation settings</button>
      </form>
    </section>
  );
}
