import { useEffect, useState } from "react";
type Api = (path: string, method?: string, body?: unknown) => Promise<any>;
export function AccountsPanel({
  api,
  tenant,
  admin,
  onChange,
}: {
  api: Api;
  tenant?: string;
  admin?: boolean;
  onChange?: () => Promise<void>;
}) {
  const [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [members, setMembers] = useState<
      { id: string; display_name: string; role: string; active: boolean }[]
    >([]);
  async function reload() {
    if (tenant && admin) setMembers(await api(`/tenants/${tenant}/members`));
  }
  useEffect(() => {
    reload().catch((e) => setError(e.message));
  }, [tenant, admin]);
  async function send(path: string, body: unknown, method = "POST") {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await api(path, method, body);
      setMessage(
        "Request completed. Check the local mail inbox for invitation or reset codes.",
      );
      await reload();
      await onChange?.();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel operations">
      <h2>{tenant ? "Organization and accounts" : "Account access"}</h2>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {message && <p role="status">{message}</p>}
      {tenant && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            send(
              "/organizations",
              Object.fromEntries(new FormData(e.currentTarget)),
            );
          }}
        >
          <label>
            New organization name
            <input name="name" required maxLength={100} />
          </label>
          <button disabled={busy}>Create organization</button>
        </form>
      )}
      {admin && tenant && (
        <>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              send(
                `/tenants/${tenant}/invitations`,
                Object.fromEntries(new FormData(e.currentTarget)),
              );
            }}
          >
            <label>
              Invite email
              <input name="email" type="email" required />
            </label>
            <label>
              Role
              <select name="role">
                {["REQUESTER", "AGENT", "LEAD", "ADMIN"].map((r) => (
                  <option key={r}>{r}</option>
                ))}
              </select>
            </label>
            <button disabled={busy}>Send invitation</button>
          </form>
          {members.map((m) => (
            <div className="comment" key={m.id}>
              <b>{m.display_name}</b>
              <label>
                Role
                <select
                  disabled={busy}
                  value={m.role}
                  onChange={(e) =>
                    send(
                      `/tenants/${tenant}/members/${m.id}`,
                      { role: e.target.value, active: m.active },
                      "PUT",
                    )
                  }
                >
                  {["REQUESTER", "AGENT", "LEAD", "ADMIN"].map((r) => (
                    <option key={r}>{r}</option>
                  ))}
                </select>
              </label>
              <button
                disabled={busy}
                onClick={() =>
                  send(
                    `/tenants/${tenant}/members/${m.id}`,
                    { role: m.role, active: !m.active },
                    "PUT",
                  )
                }
              >
                {m.active ? "Deactivate" : "Reactivate"}
              </button>
            </div>
          ))}
        </>
      )}
      <details>
        <summary>Accept an invitation</summary>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            send(
              "/account/accept",
              Object.fromEntries(new FormData(e.currentTarget)),
            );
          }}
        >
          <label>
            Invitation code
            <input name="token" required />
          </label>
          <label>
            Display name
            <input name="name" required maxLength={100} />
          </label>
          <label>
            Password for a new account
            <input
              name="password"
              type="password"
              minLength={12}
              maxLength={72}
              required
            />
          </label>
          <p className="muted">
            Existing users must sign in first; their password is not changed.
          </p>
          <button disabled={busy}>Accept invitation</button>
        </form>
      </details>
      <details>
        <summary>Reset password</summary>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            send(
              "/account/reset-request",
              Object.fromEntries(new FormData(e.currentTarget)),
            );
          }}
        >
          <label>
            Account email
            <input type="email" name="email" required />
          </label>
          <button disabled={busy}>Request reset code</button>
        </form>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            send(
              "/account/reset",
              Object.fromEntries(new FormData(e.currentTarget)),
            );
          }}
        >
          <label>
            Reset code
            <input name="token" required />
          </label>
          <label>
            New password
            <input
              name="password"
              type="password"
              required
              minLength={12}
              maxLength={72}
            />
          </label>
          <button disabled={busy}>Reset password</button>
        </form>
      </details>
      {tenant && (
        <button
          disabled={busy}
          onClick={() => send("/account/revoke-sessions", {})}
        >
          Sign out all sessions
        </button>
      )}
    </section>
  );
}
