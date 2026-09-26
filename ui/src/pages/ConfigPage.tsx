import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { get, type ConfigRow } from "../api";
import { ErrorNote, Loading, Panel, Table } from "../components/ui";

export function ConfigPage() {
  const configs = useQuery({ queryKey: ["configs"], queryFn: () => get<ConfigRow[]>("/api/configs") });
  const [file, setFile] = useState<string | null>(null);
  const content = useQuery({
    queryKey: ["config", file],
    enabled: file != null,
    queryFn: () => get<{ file: string; text: string }>(`/api/configs/content?file=${encodeURIComponent(file!)}`),
  });
  if (configs.isLoading) return <Loading what="config files" />;
  if (configs.error) return <ErrorNote error={configs.error} />;
  return (
    <div className="space-y-4">
      <Panel title="Versioned config files">
        <p className="mb-3 text-xs text-muted">A change is a new version file; the content hash is stamped on every run and session.</p>
        <Table rows={configs.data!} columns={[
          { key: "f", label: "File", render: (r) => <button className="text-accent underline" onClick={() => setFile(r.file)}>{r.file}</button> },
          { key: "v", label: "Version", render: (r) => r.version ?? "–" },
          { key: "s", label: "Status", render: (r) => r.error ? <span className="text-down">INVALID</span> : r.status },
          { key: "h", label: "Hash", render: (r) => <span className="num text-xs">{r.hash?.slice(0, 19) ?? r.error}</span> },
        ]} />
      </Panel>
      {file && (
        <Panel title={file}>
          {content.isLoading ? <Loading what="file" /> : <pre className="num max-h-[32rem] overflow-auto text-xs">{content.data?.text}</pre>}
        </Panel>
      )}
    </div>
  );
}
