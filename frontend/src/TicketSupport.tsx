import { useEffect, useState } from "react";
type Api = (path: string, method?: string, body?: unknown) => Promise<any>;
export function TicketSupport({
  tenant,
  ticket,
  staff,
  api,
}: {
  tenant: string;
  ticket: string;
  staff: boolean;
  api: Api;
}) {
  const [files, setFiles] = useState<
      { id: string; name: string; size: number }[]
    >([]),
    [approvals, setApprovals] = useState<
      { id: string; reason: string; decision: string | null }[]
    >([]),
    [members, setMembers] = useState<
      { id: string; display_name: string; role: string }[]
    >([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const [cycles, setCycles] = useState<
    { cycle: number; summary: string; breached: boolean; resolved_at: string }[]
  >([]);
  const base = `/tenants/${tenant}/tickets/${ticket}`;
  useEffect(() => {
    let active = true;
    api(base + "/resolution-cycles")
      .then((v) => {
        if (active) setCycles(v);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [tenant, ticket]);
  async function reload() {
    const [f, a] = await Promise.all([
      api(base + "/attachments"),
      api(base + "/approvals"),
    ]);
    setFiles(f);
    setApprovals(a);
  }
  useEffect(() => {
    let active = true;
    Promise.all([
      api(base + "/attachments"),
      api(base + "/approvals"),
      staff ? api(`/tenants/${tenant}/members`) : Promise.resolve([]),
    ])
      .then(([f, a, m]) => {
        if (active) {
          setFiles(f);
          setApprovals(a);
          setMembers(m);
        }
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [tenant, ticket, staff]);
  async function work(action: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await action();
      await reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section>
      {cycles.length > 0 && (
        <>
          <h3>Previous resolution cycles</h3>
          {cycles.map((c) => (
            <article className="comment" key={c.cycle}>
              <b>
                Cycle {c.cycle} · {c.breached ? "Breached" : "Within target"}
              </b>
              <p>{c.summary}</p>
              <small>{new Date(c.resolved_at).toLocaleString()}</small>
            </article>
          ))}
        </>
      )}
      <h3>Attachments</h3>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <p className="muted">
        Up to 2 MiB per file. TXT, PNG, JPG or PDF. Files are downloaded, not
        previewed.
      </p>
      {files.map((f) => (
        <p key={f.id}>
          <a href={"/api" + base + "/attachments/" + f.id}>{f.name}</a> ·{" "}
          {Math.ceil(f.size / 1024)} KiB
          <button
            disabled={busy}
            onClick={() =>
              work(() => api(base + "/attachments/" + f.id, "DELETE"))
            }
          >
            Delete attachment
          </button>
        </p>
      ))}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const data = new FormData(e.currentTarget);
          work(() => api(base + "/attachments", "POST", data));
        }}
      >
        <label>
          Attach file
          <input
            type="file"
            name="file"
            accept=".txt,.png,.jpg,.pdf"
            required
          />
        </label>
        <button disabled={busy}>Upload</button>
      </form>
      <h3>Approvals</h3>
      {approvals.map((a) => (
        <article className="comment" key={a.id}>
          <p>{a.reason}</p>
          <b>{a.decision || "Pending"}</b>
          {staff && !a.decision && (
            <div>
              <button
                disabled={busy}
                onClick={() =>
                  work(() =>
                    api(base + "/approvals/" + a.id, "POST", {
                      decision: "APPROVED",
                    }),
                  )
                }
              >
                Approve
              </button>{" "}
              <button
                disabled={busy}
                onClick={() =>
                  work(() =>
                    api(base + "/approvals/" + a.id, "POST", {
                      decision: "REJECTED",
                    }),
                  )
                }
              >
                Reject
              </button>
            </div>
          )}
        </article>
      ))}
      {staff && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            work(() =>
              api(base + "/approvals", "POST", Object.fromEntries(data)),
            );
          }}
        >
          <label>
            Approver
            <select name="approverId" required>
              <option value="">Choose an eligible colleague</option>
              {members
                .filter((m) => m.role !== "REQUESTER")
                .map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.display_name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            Reason
            <textarea name="reason" required maxLength={2000} />
          </label>
          <button disabled={busy}>Request approval</button>
        </form>
      )}
    </section>
  );
}
