import { useEffect, useState } from "react";
type Api = (path: string, method?: string, body?: unknown) => Promise<any>;
export function SlaSettings({ tenant, api }: { tenant: string; api: Api }) {
  const [policies, setPolicies] = useState<
      { id: string; priority: string; version: number; definition: string }[]
    >([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [business, setBusiness] = useState(false);
  const path = `/tenants/${tenant}/sla-policies`;
  useEffect(() => {
    let active = true;
    api(path)
      .then((v) => {
        if (active) setPolicies(v);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [tenant]);
  return (
    <section className="panel operations">
      <h2>SLA policies</h2>
      <p className="muted">
        Saving creates a new version for future tickets. Existing tickets keep
        their original policy and deadlines. Without a saved policy, the default
        24/7 target applies.
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <ul>
        {policies.map((p) => {
          const d = JSON.parse(p.definition);
          return (
            <li key={p.id}>
              {p.priority} · version {p.version} · response {d.responseMinutes}{" "}
              min · resolution {d.resolutionMinutes} min ·{" "}
              {d.businessHours ? "business hours" : "24/7"}
            </li>
          );
        })}
      </ul>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const data = new FormData(e.currentTarget);
          setBusy(true);
          setError("");
          try {
            await api(path, "POST", {
              priority: data.get("priority"),
              responseMinutes: Number(data.get("response")),
              resolutionMinutes: Number(data.get("resolution")),
              businessHours: business,
              zone: data.get("zone") || "UTC",
              opens: data.get("opens") || "09:00",
              closes: data.get("closes") || "17:00",
              days: business ? data.getAll("days").map(Number) : [],
              holidays: business
                ? String(data.get("holidays") || "")
                    .split(",")
                    .map((s) => s.trim())
                    .filter(Boolean)
                : [],
            });
            setPolicies(await api(path));
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label>
          Priority
          <select name="priority">
            {["P1", "P2", "P3", "P4"].map((p) => (
              <option key={p}>{p}</option>
            ))}
          </select>
        </label>
        <label>
          First response (minutes)
          <input
            name="response"
            type="number"
            min="1"
            max="525600"
            required
            defaultValue="60"
          />
        </label>
        <label>
          Resolution (minutes)
          <input
            name="resolution"
            type="number"
            min="1"
            max="525600"
            required
            defaultValue="480"
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={business}
            onChange={(e) => setBusiness(e.target.checked)}
          />
          Count business hours only
        </label>
        {business && (
          <>
            <label>
              IANA time zone
              <input name="zone" required defaultValue="Asia/Kolkata" />
            </label>
            <label>
              Opening time
              <input name="opens" type="time" required defaultValue="09:00" />
            </label>
            <label>
              Closing time
              <input name="closes" type="time" required defaultValue="17:00" />
            </label>
            <fieldset>
              <legend>Working days</legend>
              {[
                "Monday",
                "Tuesday",
                "Wednesday",
                "Thursday",
                "Friday",
                "Saturday",
                "Sunday",
              ].map((day, i) => (
                <label className="check" key={day}>
                  <input
                    type="checkbox"
                    name="days"
                    value={i + 1}
                    defaultChecked={i < 5}
                  />
                  {day}
                </label>
              ))}
            </fieldset>
            <label>
              Holidays (YYYY-MM-DD, separated by commas)
              <textarea name="holidays" />
            </label>
          </>
        )}
        <button disabled={busy} className="primary">
          Save policy version
        </button>
      </form>
    </section>
  );
}
