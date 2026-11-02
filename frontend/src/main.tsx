import React, { useEffect, useState, useRef } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";
import { Operations } from "./Operations";
import { Assignment } from "./Assignment";
import { TicketSupport } from "./TicketSupport";
import { SlaSettings } from "./SlaSettings";
import { AccountsPanel } from "./AccountsPanel";
import { useLive } from "./useLive";
import { EscalationSettings } from "./EscalationSettings";
type Membership = { id: string; name: string; role: string };
type Ticket = {
  ticket_number: number;
  ticket_type: string;
  id: string;
  title: string;
  description: string;
  status: string;
  priority: string;
  version: number;
  response_due: string;
  resolution_due: string;
};
type Detail = {
  ticket: Ticket;
  comments: {
    id: string;
    display_name: string;
    body: string;
    internal: boolean;
  }[];
};
let csrf: { token: string; headerName: string } | undefined;
async function api(
  path: string,
  method = "GET",
  body?: unknown,
  requestKey?: string,
) {
  if (method !== "GET" && !csrf)
    csrf = await fetch("/api/csrf").then((r) => r.json());
  const r = await fetch("/api" + path, {
    method,
    headers: {
      ...(requestKey ? { "Idempotency-Key": requestKey } : {}),
      ...(body instanceof FormData
        ? {}
        : body instanceof URLSearchParams
          ? { "Content-Type": "application/x-www-form-urlencoded" }
          : { "Content-Type": "application/json" }),
      ...(method !== "GET" && csrf ? { [csrf.headerName]: csrf.token } : {}),
    },
    body:
      body instanceof FormData || body instanceof URLSearchParams
        ? body
        : body === undefined
          ? undefined
          : JSON.stringify(body),
  });
  if (!r.ok) {
    if (r.status === 401) {
      csrf = undefined;
      window.dispatchEvent(new Event("desk:unauthorized"));
    }
    const problem = await r.json().catch(() => ({}));
    throw new Error(
      r.status === 401
        ? "Please sign in."
        : problem.detail || `Request failed (${r.status}).`,
    );
  }
  return r.status === 204 || r.headers.get("content-length") === "0"
    ? null
    : r.text().then((t) => (t ? JSON.parse(t) : null));
}
function App() {
  const submissionKey = useRef(crypto.randomUUID());
  const [members, setMembers] = useState<Membership[]>([]),
    [tenant, setTenant] = useState(""),
    [email, setEmail] = useState(""),
    [password, setPassword] = useState(""),
    [error, setError] = useState(""),
    [tickets, setTickets] = useState<Ticket[]>([]),
    [detail, setDetail] = useState<Detail | null>(null),
    [creating, setCreating] = useState(false),
    [busy, setBusy] = useState(false);
  const revision = useLive(tenant);
  useEffect(() => {
    const clear = () => {
      setMembers([]);
      setTenant("");
      setDetail(null);
      setTickets([]);
    };
    window.addEventListener("desk:unauthorized", clear);
    return () => window.removeEventListener("desk:unauthorized", clear);
  }, []);
  const [page, setPage] = useState(0),
    [search, setSearch] = useState(""),
    [statusFilter, setStatusFilter] = useState("");
  const [summary, setSummary] = useState("");
  const [teams, setTeams] = useState<{ id: string; name: string }[]>([]);
  useEffect(() => {
    setSummary("");
  }, [detail?.ticket.id]);
  const selected = members.find((m) => m.id === tenant);
  const base = `/tenants/${tenant}`;
  async function session() {
    const me = await api("/me");
    setMembers(me.memberships);
    setTenant((current) =>
      me.memberships.some((m: Membership) => m.id === current)
        ? current
        : me.memberships[0]?.id || "",
    );
  }
  useEffect(() => {
    session().catch(() => {});
  }, []);
  useEffect(() => {
    setDetail(null);
    setTickets([]);
    setTeams([]);
  }, [tenant]);
  useEffect(() => {
    let active = true;
    if (tenant) {
      api(
        base +
          "/tickets?page=" +
          page +
          "&search=" +
          encodeURIComponent(search) +
          "&status=" +
          statusFilter,
      )
        .then((v) => {
          if (active) setTickets(v);
        })
        .catch((e) => {
          if (active) setError(e.message);
        });
      api(base + "/teams")
        .then((v) => {
          if (active) setTeams(v);
        })
        .catch((e) => {
          if (active) setError(e.message);
        });
    }
    return () => {
      active = false;
    };
  }, [tenant, revision, page, search, statusFilter]);
  useEffect(() => {
    let active = true;
    if (tenant && detail)
      api(base + "/tickets/" + detail.ticket.id)
        .then((v) => {
          if (active) setDetail(v);
        })
        .catch((e) => {
          if (active) {
            setDetail(null);
            setError(e.message);
          }
        });
    return () => {
      active = false;
    };
  }, [revision]);
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function refresh(id?: string) {
    setTickets(
      await api(
        base +
          "/tickets?page=" +
          page +
          "&search=" +
          encodeURIComponent(search) +
          "&status=" +
          statusFilter,
      ),
    );
    if (id) setDetail(await api(base + "/tickets/" + id));
  }
  return (
    <div className="shell">
      <aside>
        <div className="brand">▦ Service Desk</div>
        <p className="muted">SUPPORT OPERATIONS</p>
        <div className="nav">▤ &nbsp; Service queue</div>
        <div className="aside-bottom">
          Local workspace
          <br />
          <small>Organization-isolated support</small>
        </div>
      </aside>
      <main>
        <header>
          <span>Workspace / Service queue</span>
          {members.length > 0 && (
            <button
              onClick={() =>
                run(async () => {
                  await api("/logout", "POST");
                  csrf = undefined;
                  setMembers([]);
                  setTenant("");
                  setDetail(null);
                })
              }
            >
              Sign out
            </button>
          )}
        </header>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        {members.length === 0 ? (
          <section className="login">
            <p className="eyebrow">WELCOME BACK</p>
            <h1>Your support workspace.</h1>
            <p>Sign in to manage requests and keep work moving.</p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                run(async () => {
                  await api(
                    "/login",
                    "POST",
                    new URLSearchParams({ username: email, password }),
                  );
                  csrf = undefined;
                  await session();
                });
              }}
            >
              <label>
                Email
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </label>
              <label>
                Password
                <input
                  type="password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </label>
              <button disabled={busy} className="primary">
                Sign in
              </button>
            </form>
            <AccountsPanel api={api} />
          </section>
        ) : (
          <>
            <div className="heading">
              <div>
                <p className="eyebrow">SERVICE OPERATIONS</p>
                <h1>Service queue</h1>
                <p className="muted">
                  Track requests, coordinate support, and watch deadlines.
                </p>
              </div>
              <button
                className="primary"
                onClick={() => {
                  if (!creating) submissionKey.current = crypto.randomUUID();
                  setCreating(!creating);
                }}
              >
                + New ticket
              </button>
            </div>
            <label className="org">
              Organization
              <select
                value={tenant}
                onChange={(e) => setTenant(e.target.value)}
              >
                {members.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name} · {m.role}
                  </option>
                ))}
              </select>
            </label>
            <div className="metrics">
              <article>
                <small>Loaded tickets</small>
                <strong>{tickets.length}</strong>
              </article>
              <article>
                <small>In progress</small>
                <strong>
                  {tickets.filter((t) => t.status === "IN_PROGRESS").length}
                </strong>
              </article>
              <article>
                <small>Awaiting customer</small>
                <strong>
                  {
                    tickets.filter((t) => t.status === "WAITING_FOR_CUSTOMER")
                      .length
                  }
                </strong>
              </article>
            </div>
            {creating && (
              <section className="panel">
                <h2>Create a ticket</h2>
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const data = new FormData(e.currentTarget);
                    run(async () => {
                      const result = await api(
                        base + "/tickets",
                        "POST",
                        Object.fromEntries(data),
                        submissionKey.current,
                      );
                      setCreating(false);
                      await refresh(result.id);
                    });
                  }}
                >
                  <label>
                    Title
                    <input name="title" required maxLength={200} />
                  </label>
                  <label>
                    Ticket type
                    <select name="ticketType">
                      <option value="INCIDENT">
                        Incident — something is broken
                      </option>
                      <option value="REQUEST">
                        Service request — access, software or equipment
                      </option>
                    </select>
                  </label>
                  <label>
                    Description
                    <textarea name="description" required maxLength={20000} />
                  </label>
                  <label>
                    Support team
                    <select name="teamId" required>
                      <option value="">Choose a team</option>
                      {teams.map((t) => (
                        <option key={t.id} value={t.id}>
                          {t.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Priority
                    <select name="priority" defaultValue="P3">
                      {["P1", "P2", "P3", "P4"].map((p) => (
                        <option key={p}>{p}</option>
                      ))}
                    </select>
                  </label>
                  <button disabled={busy} className="primary">
                    Submit ticket
                  </button>
                </form>
              </section>
            )}
            <div className="filters">
              <label>
                Search tickets
                <input
                  value={search}
                  onChange={(e) => {
                    setSearch(e.target.value);
                    setPage(0);
                  }}
                  maxLength={200}
                />
              </label>
              <label>
                Status
                <select
                  value={statusFilter}
                  onChange={(e) => {
                    setStatusFilter(e.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">All statuses</option>
                  {[
                    "OPEN",
                    "IN_PROGRESS",
                    "WAITING_FOR_CUSTOMER",
                    "RESOLVED",
                    "CLOSED",
                    "CANCELLED",
                  ].map((v) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
              </label>
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
              >
                Previous
              </button>
              <span>Page {page + 1}</span>
              <button
                disabled={tickets.length < 50}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </button>
            </div>
            <div className="workspace">
              <section className="panel">
                <h2>
                  Tickets <span className="count">{tickets.length}</span>
                </h2>
                {tickets.length === 0 ? (
                  <p className="empty">
                    No tickets yet. Create a request to get started.
                  </p>
                ) : (
                  tickets.map((t) => (
                    <button
                      className="ticket"
                      key={t.id}
                      onClick={() =>
                        run(async () =>
                          setDetail(await api(base + "/tickets/" + t.id)),
                        )
                      }
                    >
                      <span className={"priority " + t.priority}>
                        {t.priority}
                      </span>
                      <span>
                        <b>{t.title}</b>
                        <small>
                          SD-{t.ticket_number} ·{" "}
                          {t.ticket_type === "REQUEST"
                            ? "Service request"
                            : "Incident"}
                        </small>
                        <small>{t.status.replaceAll("_", " ")}</small>
                      </span>
                      <span>↗</span>
                    </button>
                  ))
                )}
              </section>
              {detail && (
                <section className="panel detail">
                  <p className="eyebrow">
                    {detail.ticket.priority} ·{" "}
                    {detail.ticket.status.replaceAll("_", " ")}
                  </p>
                  <h2>{detail.ticket.title}</h2>
                  <p className="muted">
                    SD-{detail.ticket.ticket_number} ·{" "}
                    {detail.ticket.ticket_type === "REQUEST"
                      ? "Service request"
                      : "Incident"}
                  </p>
                  <p className="body">{detail.ticket.description}</p>
                  <p className="muted">
                    Response due:{" "}
                    {new Date(detail.ticket.response_due).toLocaleString()}
                    <br />
                    Resolution due:{" "}
                    {new Date(detail.ticket.resolution_due).toLocaleString()}
                  </p>
                  {selected?.role !== "REQUESTER" && (
                    <label>
                      Change status
                      <select
                        value=""
                        disabled={busy}
                        onChange={(e) => {
                          const status = e.target.value;
                          if (status)
                            run(async () => {
                              await api(
                                base +
                                  "/tickets/" +
                                  detail.ticket.id +
                                  "/transitions",
                                "POST",
                                {
                                  status,
                                  version: detail.ticket.version,
                                  summary,
                                },
                              );
                              await refresh(detail.ticket.id);
                            });
                        }}
                      >
                        <option value="">Choose next status</option>
                        {(
                          {
                            OPEN: ["IN_PROGRESS", "CANCELLED"],
                            IN_PROGRESS: [
                              "WAITING_FOR_CUSTOMER",
                              "RESOLVED",
                              "CANCELLED",
                            ],
                            WAITING_FOR_CUSTOMER: ["IN_PROGRESS", "CANCELLED"],
                            RESOLVED: ["CLOSED", "IN_PROGRESS"],
                          } as Record<string, string[]>
                        )[detail.ticket.status]?.map((s) => (
                          <option key={s}>{s}</option>
                        ))}
                      </select>
                    </label>
                  )}
                  {selected?.role !== "REQUESTER" && (
                    <label>
                      Resolution summary (required when resolving)
                      <textarea
                        value={summary}
                        maxLength={10000}
                        onChange={(e) => setSummary(e.target.value)}
                      />
                    </label>
                  )}
                  {selected?.role !== "REQUESTER" && (
                    <Assignment
                      key={detail.ticket.id}
                      tenant={tenant}
                      ticket={detail.ticket.id}
                      version={detail.ticket.version}
                      admin={selected?.role === "ADMIN"}
                      api={api}
                      onChange={() => refresh(detail.ticket.id)}
                    />
                  )}
                  {selected?.role === "REQUESTER" &&
                    detail.ticket.status === "RESOLVED" && (
                      <div>
                        <button
                          onClick={() =>
                            run(async () => {
                              await api(
                                base +
                                  "/tickets/" +
                                  detail.ticket.id +
                                  "/requester-action",
                                "POST",
                                {
                                  status: "CLOSED",
                                  version: detail.ticket.version,
                                },
                              );
                              await refresh(detail.ticket.id);
                            })
                          }
                        >
                          Confirm resolution
                        </button>
                        <button
                          onClick={() =>
                            run(async () => {
                              await api(
                                base +
                                  "/tickets/" +
                                  detail.ticket.id +
                                  "/requester-action",
                                "POST",
                                {
                                  status: "IN_PROGRESS",
                                  version: detail.ticket.version,
                                },
                              );
                              await refresh(detail.ticket.id);
                            })
                          }
                        >
                          Reopen ticket
                        </button>
                      </div>
                    )}
                  <h3>Conversation</h3>
                  {detail.comments.map((c) => (
                    <article className="comment" key={c.id}>
                      <b>{c.display_name}</b>
                      {c.internal && <small> · Internal note</small>}
                      <p>{c.body}</p>
                    </article>
                  ))}
                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      const form = e.currentTarget;
                      const data = new FormData(form);
                      run(async () => {
                        await api(
                          base + "/tickets/" + detail.ticket.id + "/comments",
                          "POST",
                          {
                            body: data.get("body"),
                            internal: data.get("internal") === "on",
                          },
                        );
                        form.reset();
                        await refresh(detail.ticket.id);
                      });
                    }}
                  >
                    <label>
                      Add a reply
                      <textarea name="body" required maxLength={20000} />
                    </label>
                    {selected?.role !== "REQUESTER" && (
                      <label className="check">
                        <input type="checkbox" name="internal" />
                        Internal note
                      </label>
                    )}
                    <button disabled={busy} className="primary">
                      Post reply
                    </button>
                  </form>
                  <TicketSupport
                    key={tenant + detail.ticket.id + detail.ticket.version}
                    tenant={tenant}
                    ticket={detail.ticket.id}
                    staff={selected?.role !== "REQUESTER"}
                    api={api}
                  />
                </section>
              )}
            </div>
            <AccountsPanel
              key={tenant + "accounts"}
              tenant={tenant}
              admin={selected?.role === "ADMIN"}
              api={api}
              onChange={session}
            />
            <Operations
              key={tenant + revision}
              tenant={tenant}
              role={selected?.role || "REQUESTER"}
              api={api}
              onTeamsChanged={() => api(base + "/teams").then(setTeams)}
            />
            {selected?.role === "ADMIN" && (
              <>
                <SlaSettings key={tenant + "sla"} tenant={tenant} api={api} />
                <EscalationSettings tenant={tenant} api={api} />
              </>
            )}
          </>
        )}
      </main>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
