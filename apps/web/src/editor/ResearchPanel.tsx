import { useEffect, useMemo, useState } from "react";
import { ApiError } from "../api/client";
import { emptyReproducibility, getResearch, ResearchVersionConflict, saveResearch, type EvidenceItem, type ResearchDocument } from "../api/research";
import { Button } from "../components/Button";
import { Spinner } from "../components/Spinner";

type Tab = "quality" | "evidence" | "reproducibility";
const inputClass = "mt-1 w-full rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-brand-600 focus:ring-2 focus:ring-brand-600/20 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100";
const emptyItem = (): EvidenceItem => ({ id: crypto.randomUUID(), claim: "", citation_key: "", quote: "", locator: "", study_type: "", sample: "", findings: "", limitations: "", relevance: "", status: "to_review" });

export function ResearchPanel({ workspaceId, editable, token }: { workspaceId: string; editable: boolean; token: () => Promise<string> }) {
  const [tab, setTab] = useState<Tab>("quality");
  const [doc, setDoc] = useState<ResearchDocument | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [item, setItem] = useState<EvidenceItem>(emptyItem);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);
  const [conflict, setConflict] = useState(false);
  const canWrite = editable;

  const refresh = async (signal?: AbortSignal) => {
    const access = await token();
    const answer = await getResearch(access, workspaceId, signal);
    setDoc(answer);
    return answer;
  };
  useEffect(() => {
    const controller = new AbortController();
    void refresh(controller.signal).catch(() => { if (!controller.signal.aborted) setError("Research notes could not be loaded."); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
    // The token getter is stable for the lifetime of this editor page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  const editItem = (id: string) => {
    const found = doc?.items.find((entry) => entry.id === id);
    if (found) { setItem({ ...found }); setSelected(id); setSaved(false); }
  };
  const addItem = () => { setItem(emptyItem()); setSelected("new"); setTab("evidence"); setSaved(false); };
  const removeItem = (id: string) => {
    if (!doc) return;
    setDoc({ ...doc, items: doc.items.filter((entry) => entry.id !== id) });
    if (selected === id) { setSelected(null); setItem(emptyItem()); }
    setSaved(false);
  };
  const updateItem = (key: keyof EvidenceItem, value: string) => setItem((current) => ({ ...current, [key]: value }));
  const save = async () => {
    if (!doc) return;
    const items = [...doc.items];
    if (item.claim.trim() && item.citation_key.trim()) {
      const index = items.findIndex((entry) => entry.id === item.id);
      if (index < 0) items.push(item); else items[index] = item;
    }
    setSaving(true); setError(""); setSaved(false); setConflict(false);
    try {
      const access = await token();
      const updated = await saveResearch(access, workspaceId, { base_version: doc.version, items, reproducibility: doc.reproducibility });
      setDoc(updated);
      if (items.some((entry) => entry.id === item.id)) { setItem({ ...item }); setSelected(item.id); }
      setSaved(true);
    } catch (cause) {
      if (cause instanceof ResearchVersionConflict) { setConflict(true); setError("A co-author saved newer research notes. Reload the latest version before saving again."); }
      else setError(cause instanceof ApiError && cause.status === 403 ? "You have view access to research notes." : "Research notes could not be saved. Your edits are still here.");
    } finally { setSaving(false); }
  };
  const reloadLatest = async () => {
    if (!window.confirm("Reload the latest research notes and discard your unsaved changes?")) return;
    setError(""); setConflict(false);
    try { const latest = await refresh(); setDoc(latest); setItem(emptyItem()); setSelected(null); setSaved(false); }
    catch { setError("The latest research notes could not be loaded. Your local edits are still here."); setConflict(true); }
  };
  const refreshQuality = async () => {
    setError("");
    try {
      const answer = await getResearch(await token(), workspaceId);
      // Keep the current edit version and draft fields intact. A stale version
      // must still conflict instead of overwriting another author's changes.
      setDoc((current) => current ? { ...current, quality: answer.quality } : answer);
    } catch {
      setError("Citation coverage could not be refreshed. Your local notes are still here.");
    }
  };
  const changeRepro = (key: keyof typeof emptyReproducibility, value: string) => {
    if (doc) { setDoc({ ...doc, reproducibility: { ...doc.reproducibility, [key]: value } }); setSaved(false); }
  };
  const quality = useMemo(() => doc?.quality, [doc]);

  if (loading) return <div className="flex items-center gap-2 p-4 text-sm text-zinc-600"><Spinner /> Loading research notes</div>;
  if (!doc) return <p role="alert" className="p-4 text-sm text-red-700">{error || "Research notes are unavailable."}</p>;

  const label = (name: string, key: keyof EvidenceItem, multiline = false) => (
    <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300">{name}
      {multiline ? <textarea className={`${inputClass} min-h-20 resize-y`} value={item[key]} disabled={!canWrite} onChange={(event) => updateItem(key, event.target.value)} /> : <input className={inputClass} value={item[key]} disabled={!canWrite} onChange={(event) => updateItem(key, event.target.value)} />}
    </label>
  );
  const qualityList = (title: string, values: string[], detail: string) => <section className="rounded-lg border border-zinc-200 p-3 dark:border-zinc-800"><h3 className="text-sm font-semibold">{title} <span className="font-normal text-zinc-500">({values.length})</span></h3>{values.length ? <ul className="mt-2 list-disc pl-5 text-sm text-amber-800 dark:text-amber-300">{values.map((key) => <li key={key}><code>{key}</code></li>)}</ul> : <p className="mt-1 text-sm text-zinc-500">None found.</p>}<p className="mt-2 text-xs text-zinc-500">{detail}</p></section>;

  return <div className="flex h-full min-h-0 flex-col gap-4 overflow-y-auto p-4">
    <div role="tablist" aria-label="Research tools" className="flex gap-1 rounded-lg bg-zinc-100 p-1 dark:bg-zinc-900">
      {([["quality", "Quality check"], ["evidence", "Evidence matrix"], ["reproducibility", "Reproducibility"]] as const).map(([id, title]) => <button key={id} type="button" role="tab" aria-selected={tab === id} onClick={() => setTab(id)} className={`flex-1 rounded-md px-2 py-2 text-xs font-semibold ${tab === id ? "bg-white shadow-sm dark:bg-zinc-800" : "text-zinc-500"}`}>{title}</button>)}
    </div>
    {tab === "quality" && <div className="space-y-3">
      <div className="flex items-start justify-between gap-2"><div><h2 className="text-base font-semibold">Citation coverage</h2><p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">Checks citation keys and whether you have recorded evidence for each cited source. It cannot determine whether a source supports a claim.</p></div><Button onClick={() => void refreshQuality()}>Refresh check</Button></div>
      {quality && <>{qualityList("Citations missing from the bibliography", quality.unresolved_keys, "Add or correct the corresponding key in a project .bib file.")}{qualityList("Citations without an evidence record", quality.citations_without_evidence, "Add the claim and source passage to the evidence matrix.")}{qualityList("Unused bibliography entries", quality.unused_references, "These entries are present in .bib files but not cited in the manuscript.")}</>}
    </div>}
    {tab === "evidence" && <div className="space-y-3">
      <div className="flex items-start justify-between gap-2"><div><h2 className="text-base font-semibold">Evidence matrix</h2><p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">Record the passage and your assessment for each cited claim.</p></div>{canWrite && <Button onClick={addItem}>Add source</Button>}</div>
      {doc.items.length === 0 && <p className="rounded-lg border border-dashed border-zinc-300 p-4 text-sm text-zinc-500 dark:border-zinc-700">No evidence records yet. Add a citation key and the claim it is used to support.</p>}
      {doc.items.map((entry) => <div key={entry.id} className={`rounded-lg border p-3 ${selected === entry.id ? "border-brand-500" : "border-zinc-200 dark:border-zinc-800"}`}><button type="button" onClick={() => editItem(entry.id)} className="w-full text-left"><span className="font-mono text-xs text-brand-700 dark:text-brand-300">{entry.citation_key}</span><p className="mt-1 text-sm font-medium">{entry.claim}</p><p className="mt-1 line-clamp-2 text-xs text-zinc-500">{entry.quote || "No excerpt recorded"}{entry.locator ? ` · ${entry.locator}` : ""}</p><span className="mt-2 inline-block rounded-full bg-zinc-100 px-2 py-0.5 text-xs dark:bg-zinc-800">{entry.status.replaceAll("_", " ")}</span></button>{canWrite && <button type="button" className="mt-2 text-xs text-red-700 underline dark:text-red-300" onClick={() => removeItem(entry.id)}>Remove record</button>}</div>)}
      {(selected !== null || item.claim || !doc.items.length) && <div className="space-y-3 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
        <h3 className="text-sm font-semibold">{selected ? "Edit evidence record" : "New evidence record"}</h3>
        {label("Claim in the manuscript", "claim", true)}{label("BibTeX citation key", "citation_key")}{label("Supporting passage from the source", "quote", true)}{label("Page, section, or table", "locator")}
        {label("Study type", "study_type")}{label("Sample or dataset", "sample")}{label("Findings", "findings", true)}{label("Limitations", "limitations", true)}{label("Relevance to this claim", "relevance", true)}
        <label className="block text-xs font-medium text-zinc-700 dark:text-zinc-300">Researcher assessment<select className={inputClass} disabled={!canWrite} value={item.status} onChange={(event) => updateItem("status", event.target.value)}><option value="to_review">To review</option><option value="supported_by_author">I reviewed this source and consider it supportive</option><option value="needs_revision">Needs revision</option></select></label>
      </div>}
    </div>}
    {tab === "reproducibility" && <div className="space-y-3"><div><h2 className="text-base font-semibold">Reproducibility notes</h2><p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">Document what another researcher would need to inspect or rerun your work. Completing this form is not a reproducibility guarantee.</p></div>
      {([["Data availability", "data_availability"], ["Code availability", "code_availability"], ["Software and versions", "software"], ["Analysis steps", "analysis_steps"], ["Preregistration", "preregistration"], ["Additional notes", "notes"]] as const).map(([title,key]) => <label key={key} className="block text-xs font-medium text-zinc-700 dark:text-zinc-300">{title}<textarea className={`${inputClass} min-h-20 resize-y`} disabled={!canWrite} maxLength={10000} value={doc.reproducibility[key]} onChange={(event) => changeRepro(key,event.target.value)} /></label>)}
    </div>}
    {error && <div role="alert" className="space-y-2 text-sm text-red-700 dark:text-red-300"><p>{error}</p>{conflict && <Button onClick={() => void reloadLatest()}>Reload latest (discard my edits)</Button>}</div>}{saved && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-300">Research notes saved.</p>}
    {canWrite && <div className="sticky bottom-0 border-t border-zinc-200 bg-white py-3 dark:border-zinc-800 dark:bg-zinc-950"><Button onClick={() => void save()} disabled={saving}>{saving ? "Saving…" : "Save research notes"}</Button></div>}
  </div>;
}
