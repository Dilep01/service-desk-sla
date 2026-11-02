import { useEffect, useState } from "react";
type Api = (path: string, method?: string, body?: unknown) => Promise<any>;
export function Assignment({
  tenant,
  ticket,
  version,
  admin,
  api,
  onChange,
}: {
  tenant: string;
  ticket: string;
  version: number;
  admin: boolean;
  api: Api;
  onChange: () => Promise<void>;
}) {
  const [members, setMembers] = useState<
      { id: string; display_name: string; role: string }[]
    >([]),
    [teams, setTeams] = useState<{ id: string; name: string }[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const base = `/tenants/${tenant}`;
  useEffect(() => {
    let active = true;
    Promise.all([api(base + "/members"), api(base + "/teams")])
      .then(([m, t]) => {
        if (active) {
          setMembers(m);
          setTeams(t);
        }
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [tenant]);
  async function update(path: string, method: string, body: unknown) {
    setBusy(true);
    setError("");
    try {
      await api(base + path, method, body);
      await onChange();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <label>
        Assign agent
        <select
          value=""
          disabled={busy}
          onChange={(e) => {
            if (e.target.value)
              update("/tickets/" + ticket + "/assignment", "POST", {
                assignee: e.target.value,
                version,
              });
          }}
        >
          <option value="">Choose an eligible team member</option>
          {members
            .filter((m) => m.role !== "REQUESTER")
            .map((m) => (
              <option value={m.id} key={m.id}>
                {m.display_name}
              </option>
            ))}
        </select>
      </label>
      {admin && (
        <label>
          Route to team (clears current assignment)
          <select
            value=""
            disabled={busy}
            onChange={(e) => {
              if (e.target.value)
                update("/teams/route/" + ticket, "PUT", {
                  teamId: e.target.value,
                  version,
                });
            }}
          >
            <option value="">Choose team</option>
            {teams.map((t) => (
              <option value={t.id} key={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
      )}
      <details>
        <summary>Change priority</summary>
        <p className="muted">
          Recalculates targets from the original creation time using the latest
          policy. Previous breach flags remain recorded.
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            update("/tickets/" + ticket + "/priority", "POST", {
              priority: data.get("priority"),
              reason: data.get("reason"),
              version,
            });
          }}
        >
          <label>
            New priority
            <select name="priority">
              {["P1", "P2", "P3", "P4"].map((p) => (
                <option key={p}>{p}</option>
              ))}
            </select>
          </label>
          <label>
            Reason for priority change
            <textarea name="reason" required maxLength={2000} />
          </label>
          <button disabled={busy}>Change priority</button>
        </form>
      </details>
    </div>
  );
}
