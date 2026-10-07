'use client';
import dynamic from 'next/dynamic';
import { useEffect, useRef, useState } from 'react';
const Viewer = dynamic(() => import('../components/Viewer'), { ssr: false });
const API = process.env.NEXT_PUBLIC_API_URL || 'http://127.0.0.1:8080';
type Snapshot = { id: string; status: string; attempt: number; source: string; diagnostics: string; artifactAvailable: boolean };
const examples = ['A 60 × 40 × 20 mm enclosure with 2 mm walls and an open top', 'A 30 mm diameter knob with a 6 mm shaft hole', 'An L bracket, 50 mm legs, 20 mm wide, 4 mm thick'];

export default function Dashboard() {
  const [prompt, setPrompt] = useState(''); const [source, setSource] = useState('');
  const [status, setStatus] = useState('idle'); const [attempt, setAttempt] = useState(0);
  const [logs, setLogs] = useState<string[]>([]); const [error, setError] = useState('');
  const [busy, setBusy] = useState(false); const [modelId, setModelId] = useState<string | null>(null);
  const [sourceId, setSourceId] = useState<string | null>(null);
  const jobId = useRef<string | null>(null); const stream = useRef<EventSource | null>(null);
  const mounted = useRef(true); const logEnd = useRef<HTMLDivElement | null>(null);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; stream.current?.close(); }; }, []);
  useEffect(() => { logEnd.current?.scrollIntoView?.({ behavior: 'smooth' }); }, [logs]);
  function apply(snapshot: Snapshot) {
    setStatus(snapshot.status); setAttempt(snapshot.attempt);
    if (['succeeded', 'failed', 'cancelled'].includes(snapshot.status)) {
      setBusy(false); stream.current?.close();
      if (snapshot.status === 'succeeded') setModelId(snapshot.id);
      else if (snapshot.status === 'failed') setError(snapshot.diagnostics || 'Job failed.');
    }
  }
  async function submit(mode: 'prompt' | 'source') {
    setBusy(true); setError(''); setLogs([]); setStatus('submitting'); setAttempt(0);
    stream.current?.close(); jobId.current = null; setSourceId(null);
    try {
      const response = await fetch(`${API}/api/jobs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(mode === 'prompt' ? { prompt } : { source }) });
      if (!response.ok) {
        if (response.status === 409) throw new Error('Another job is running. Wait for it to finish.');
        throw new Error(`Submission failed (HTTP ${response.status}).`);
      }
      const { id } = await response.json(); if (!mounted.current) return;
      jobId.current = id;
      const events = new EventSource(`${API}/api/jobs/${id}/events`); stream.current = events;
      events.addEventListener('status', event => apply(JSON.parse((event as MessageEvent).data)));
      events.addEventListener('source', event => { const data = JSON.parse((event as MessageEvent).data); setSource(data.source); setAttempt(data.attempt); setSourceId(id); });
      events.addEventListener('log', event => { const data = JSON.parse((event as MessageEvent).data); setLogs(previous => [...previous, data.message].slice(-300)); });
      events.addEventListener('complete', event => apply(JSON.parse((event as MessageEvent).data)));
      events.onerror = async () => {
        setLogs(previous => [...previous.slice(-299), 'Stream interrupted. Reconnecting…']);
        try {
          const response = await fetch(`${API}/api/jobs/${id}`);
          if (response.status === 404) { events.close(); setBusy(false); setError('Job expired or backend restarted.'); setStatus('failed'); }
          else if (response.ok) { const snapshot: Snapshot = await response.json(); if (snapshot.source) { setSource(snapshot.source); setSourceId(id); } apply(snapshot); }
        } catch { /* EventSource reconnects with its last event ID. */ }
      };
    } catch (error) { if (mounted.current) { setBusy(false); setStatus('failed'); setError(error instanceof Error ? error.message : 'Unable to contact backend.'); } }
  }
  async function cancel() {
    if (!jobId.current) return;
    try { const response = await fetch(`${API}/api/jobs/${jobId.current}`, { method: 'DELETE' }); if (!response.ok) throw new Error('Unable to cancel job.'); setStatus('cancelling'); }
    catch (error) { setError(error instanceof Error ? error.message : 'Unable to cancel job.'); }
  }
  return <main className="mx-auto max-w-[1680px] p-5 md:p-8">
    <header className="mb-8 flex items-center justify-between border-b border-gray-800 pb-6"><div className="flex items-center gap-3"><span className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#b4ee49] text-2xl font-bold text-black">◇</span><div><h1 className="text-xl font-semibold tracking-tight">parametrix<span className="text-[#b4ee49]">.</span></h1><p className="text-xs text-gray-500">IDEAS INTO GEOMETRY</p></div></div><span className="rounded-full border border-gray-800 px-3 py-1.5 text-xs text-gray-400">LOCAL WORKSPACE</span></header>
    <div className="mb-6"><h2 className="text-3xl font-semibold tracking-tight">What will you make?</h2><p className="mt-2 text-sm text-gray-400">Describe your part. Refine the code. Explore the result.</p></div>
    <div className="grid gap-5 lg:grid-cols-[380px_1fr]">
      <section className="space-y-5">
        <div className="rounded-2xl border border-gray-800 bg-[#12151b] p-5"><div className="mb-4 flex justify-between"><h3 className="text-sm font-semibold">01 / Describe your model</h3><span className="text-xs text-gray-500">mm</span></div>
          <label htmlFor="prompt" className="sr-only">Model description</label><textarea id="prompt" value={prompt} onChange={event => setPrompt(event.target.value)} disabled={busy} maxLength={10000} rows={6} placeholder="A small enclosure, 60 mm wide, with rounded corners and mounting holes…" className="w-full rounded-xl border border-gray-700 bg-[#0c0e12] p-3 text-sm leading-6 placeholder:text-gray-600 focus:border-[#b4ee49]" />
          <div className="my-3 flex flex-wrap gap-2">{examples.map((example, index) => <button key={example} disabled={busy} onClick={() => setPrompt(example)} className="rounded-md border border-gray-700 px-2 py-1 text-xs text-gray-400 hover:text-white">{['Enclosure', 'Knob', 'Bracket'][index]}</button>)}</div>
          <button disabled={busy || !prompt.trim()} onClick={() => submit('prompt')} className="w-full rounded-lg bg-[#b4ee49] py-3 text-sm font-semibold text-black">{busy ? 'Working…' : 'Generate model ↗'}</button>
          {busy && <button disabled={!jobId.current || status === 'cancelling'} onClick={cancel} className="mt-2 w-full py-2 text-sm text-gray-300">Cancel job</button>}
        </div>
        <div className="rounded-2xl border border-gray-800 bg-[#12151b] p-5"><div className="mb-4 flex items-center justify-between"><h3 className="text-sm font-semibold">02 / Execution log</h3><span className="text-xs text-[#b4ee49]" role="status">{status}{attempt > 0 && ` · ${attempt}/4`}</span></div>
          <div className="h-52 overflow-auto font-mono text-xs leading-5 text-gray-400" aria-label="Execution log" aria-live="polite">{logs.length ? logs.map((line, index) => <p className="mb-2 whitespace-pre-wrap break-words" key={index}><span className="mr-2 text-gray-600">›</span>{line}</p>) : <p className="text-gray-600">Ready when you are.</p>}<div ref={logEnd} /></div>
        </div>
        {error && <div role="alert" className="rounded-xl border border-red-900 bg-red-950/30 p-4 text-sm text-red-300">{error}</div>}
      </section>
      <section className="min-w-0 space-y-5">
        <div className="overflow-hidden rounded-2xl border border-gray-800"><div className="flex items-center justify-between bg-[#171b22] px-5 py-3"><h3 className="text-sm font-semibold">Model preview</h3><span className="text-xs text-gray-500">STL · {modelId ? 'Rendered' : 'Awaiting geometry'}</span></div><div className="h-[460px]"><Viewer url={modelId ? `${API}/api/jobs/${modelId}/model.stl` : null} /></div><div className="flex justify-between bg-[#171b22] px-5 py-3 text-xs"><span className="text-gray-500">Units: millimetres</span>{modelId && <a href={`${API}/api/jobs/${modelId}/model.stl`} className="text-[#b4ee49]">Download STL ↓</a>}</div></div>
        <div className="rounded-2xl border border-gray-800 bg-[#12151b]"><div className="flex items-center justify-between border-b border-gray-800 px-5 py-3"><h3 className="text-sm font-semibold">03 / Parametric source</h3><div className="flex items-center gap-4">{sourceId && <a href={`${API}/api/jobs/${sourceId}/model.scad`} className="text-xs text-gray-400">Download SCAD ↓</a>}<button disabled={busy || !source.trim()} onClick={() => submit('source')} className="rounded-md border border-gray-600 px-3 py-1.5 text-xs text-[#b4ee49]">Render code ▷</button></div></div>
          <label htmlFor="source" className="sr-only">OpenSCAD source</label><textarea id="source" spellCheck={false} disabled={busy} value={source} onChange={event => { setSource(event.target.value); setSourceId(null); }} maxLength={100000} rows={12} placeholder="// Generated OpenSCAD will appear here.\n// You can also paste your own code and click Render." className="w-full bg-transparent p-5 font-mono text-xs leading-6 text-gray-300 placeholder:text-gray-600" />
        </div>
      </section>
    </div><footer className="mt-6 text-xs text-gray-600">Self-contained OpenSCAD · 30s render limit · Up to 3 automatic repairs</footer>
  </main>;
}
