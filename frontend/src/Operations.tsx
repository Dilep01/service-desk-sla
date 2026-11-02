import { useEffect, useState } from "react";
import { SlaReport } from "./SlaReport";
type Row = Record<string, string | number | null>;
type Api = (path: string, method?: string, body?: unknown) => Promise<any>;
export function Operations({
  tenant,
  role,
  api,
  onTeamsChanged,
}: {
  tenant: string;
  role: string;
  api: Api;
  onTeamsChanged: () => void;
}) {
  const [tab, setTab] = useState("notifications"),
    [rows, setRows] = useState<Row[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const [teams, setTeams] = useState<Row[]>([]),
    [members, setMembers] = useState<Row[]>([]);
  const base = `/tenants/${tenant}`;
  useEffect(() => {
    setTab("notifications");
    setRows([]);
    setError("");
  }, [tenant]);
  useEffect(() => {
    let active = true;
    setRows([]);
    setError("");
    api(base + "/" + tab)
      .then((v) => {
        if (active) setRows(v);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [tenant, tab]);
  useEffect(() => {
    let active = true;
    if (role === "ADMIN")
      Promise.all([api(base + "/teams"), api(base + "/members")])
        .then(([t, m]) => {
          if (active) {
            setTeams(t);
            setMembers(m);
          }
        })
        .catch((e) => {
          if (active) setError(e.message);
        });
    return () => {
      active = false;
    };
  }, [tenant, role, rows]);
  async function submit(work: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await work();
      setRows(await api(base + "/" + tab));
      onTeamsChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel operations">
      <div className="tabs">
        <button onClick={() => setTab("notifications")}>Notifications</button>
        <button onClick={() => setTab("articles")}>Knowledge base</button>
        {role === "ADMIN" && (
          <>
            <button onClick={() => setTab("reports")}>Reports</button>
            <button onClick={() => setTab("teams")}>Teams</button>
            <button onClick={() => setTab("delivery")}>Email delivery</button>
            <button onClick={() => setTab("audit")}>Audit</button>
          </>
        )}
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {rows.length === 0 && <p className="muted">No records yet.</p>}
      {tab === "notifications" &&
        rows.map((r) => (
          <p key={String(r.id)}>
            {r.message}
            {!r.read_at && (
              <button
                disabled={busy}
                onClick={() =>
                  submit(async () => {
                    await api(
                      base + "/notifications/" + r.id + "/read",
                      "POST",
                    );
                  })
                }
              >
                Mark read
              </button>
            )}
            <small className="muted">
              {" "}
              · {new Date(String(r.created_at)).toLocaleString()}
            </small>
          </p>
        ))}
      {tab === "articles" && (
        <>
          {rows.map((r) => (
            <article key={String(r.id)}>
              <h3>{r.title}</h3>
              <p className="body">{r.body}</p>
              {role === "ADMIN" && (
                <details>
                  <summary>Edit article</summary>
                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      const data = new FormData(e.currentTarget);
                      submit(async () => {
                        await api(base + "/articles/" + r.id, "PUT", {
                          title: data.get("title"),
                          body: data.get("body"),
                          published: data.get("published") === "on",
                        });
                      });
                    }}
                  >
                    <label>
                      Updated title
                      <input
                        name="title"
                        defaultValue={String(r.title)}
                        required
                        maxLength={200}
                      />
                    </label>
                    <label>
                      Updated body
                      <textarea
                        name="body"
                        defaultValue={String(r.body)}
                        required
                        maxLength={40000}
                      />
                    </label>
                    <label className="check">
                      <input
                        type="checkbox"
                        name="published"
                        defaultChecked={!!r.published}
                      />
                      Published
                    </label>
                    <button disabled={busy}>Save article</button>
                  </form>
                </details>
              )}
            </article>
          ))}
          {role === "ADMIN" && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const form = e.currentTarget;
                const data = new FormData(form);
                submit(async () => {
                  await api(base + "/articles", "POST", {
                    title: data.get("title"),
                    body: data.get("body"),
                    published: data.get("published") === "on",
                  });
                  form.reset();
                });
              }}
            >
              <label>
                Article title
                <input name="title" required maxLength={200} />
              </label>
              <label>
                Article body
                <textarea name="body" required maxLength={40000} />
              </label>
              <label className="check">
                <input type="checkbox" name="published" />
                Publish for employees
              </label>
              <button disabled={busy}>Create article</button>
            </form>
          )}
        </>
      )}
      {tab === "reports" && (
        <>
          <SlaReport tenant={tenant} api={api} />
          <table>
            <thead>
              <tr>
                <th>Status</th>
                <th>Tickets</th>
                <th>Breached tickets</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={String(r.status)}>
                  <td>{r.status}</td>
                  <td>{r.total}</td>
                  <td>{r.breached}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {tab === "delivery" &&
        rows.map((r) => (
          <article className="comment" key={String(r.id)}>
            <b>{r.subject}</b>
            <p>
              {r.recipient} · attempts {r.attempts} ·{" "}
              {r.delivered_at ? "Delivered" : r.last_error || "Pending"}
            </p>
            {!r.delivered_at && (
              <button
                disabled={busy}
                onClick={() =>
                  submit(async () => {
                    await api(base + "/delivery/" + r.id + "/retry", "POST");
                  })
                }
              >
                Retry delivery
              </button>
            )}
          </article>
        ))}
      {tab === "audit" &&
        rows.map((r) => (
          <article className="comment" key={String(r.id)}>
            <b>{r.action}</b>
            <p>{r.details}</p>
            <small>{new Date(String(r.created_at)).toLocaleString()}</small>
          </article>
        ))}
      {tab === "teams" && (
        <>
          <ul>
            {rows.map((r) => (
              <li key={String(r.id)}>{r.name}</li>
            ))}
          </ul>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const form = e.currentTarget;
              const data = new FormData(form);
              submit(async () => {
                await api(base + "/teams", "POST", { name: data.get("name") });
                form.reset();
              });
            }}
          >
            <label>
              New team name
              <input name="name" required maxLength={100} />
            </label>
            <button disabled={busy}>Create team</button>
          </form>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const data = new FormData(e.currentTarget);
              submit(async () => {
                await api(
                  base +
                    "/teams/" +
                    data.get("team") +
                    "/members/" +
                    data.get("member"),
                  "PUT",
                );
              });
            }}
          >
            <label>
              Team
              <select name="team" required>
                <option value="">Choose team</option>
                {teams.map((t) => (
                  <option key={String(t.id)} value={String(t.id)}>
                    {t.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Staff member
              <select name="member" required>
                <option value="">Choose member</option>
                {members
                  .filter((m) => m.role !== "REQUESTER")
                  .map((m) => (
                    <option key={String(m.id)} value={String(m.id)}>
                      {m.display_name} · {m.role}
                    </option>
                  ))}
              </select>
            </label>
            <button disabled={busy}>Add to team</button>
            <button
              type="button"
              disabled={busy}
              onClick={(e) => {
                const form = e.currentTarget.form;
                if (!form?.reportValidity()) return;
                const data = new FormData(form);
                submit(async () => {
                  await api(
                    base +
                      "/teams/" +
                      data.get("team") +
                      "/members/" +
                      data.get("member"),
                    "DELETE",
                  );
                });
              }}
            >
              Remove from team
            </button>
          </form>
        </>
      )}
    </section>
  );
}
