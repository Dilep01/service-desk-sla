import { useEffect, useState } from "react";
export function SlaReport({
  tenant,
  api,
}: {
  tenant: string;
  api: (path: string) => Promise<any>;
}) {
  const [report, setReport] = useState<any>(null),
    [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api(`/tenants/${tenant}/sla-report`)
      .then((r) => {
        if (active) setReport(r);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [tenant]);
  if (error) return <p role="alert">{error}</p>;
  if (!report) return <p>Loading SLA report…</p>;
  return (
    <div>
      <h3>SLA outcomes</h3>
      <p>Completed cycles are counted separately from active overdue work.</p>
      <div className="metrics">
        {(["response", "resolution"] as const).map((key) => (
          <article key={key}>
            <small>
              {key === "response" ? "First response" : "Resolution cycles"}
            </small>
            <strong>
              {report[key].completed
                ? Math.round(
                    (report[key].within_target / report[key].completed) * 100,
                  ) + "%"
                : "—"}
            </strong>
            <small>
              {report[key].within_target} of {report[key].completed} completed
              within target
            </small>
          </article>
        ))}
        <article>
          <small>Active overdue resolution</small>
          <strong>{report.active.overdue_resolution}</strong>
        </article>
      </div>
      <p>
        Unassigned active tickets: {report.active.unassigned} · overdue first
        responses: {report.response.active_overdue}
      </p>
      <h3>Agent workload</h3>
      {report.workload.map((w: any) => (
        <p key={w.display_name}>
          {w.display_name}: {w.tickets} active tickets
        </p>
      ))}
    </div>
  );
}
