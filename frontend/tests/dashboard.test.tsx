import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import Dashboard from '../app/page';
vi.mock('next/dynamic', () => ({ default: () => function Preview({ url }: { url: string | null }) { return <div data-testid="viewer">{url || 'empty'}</div>; } }));
class Events {
  static current: Events;
  handlers = new Map<string, (event: MessageEvent) => void>();
  close = vi.fn(); onerror: (() => void) | null = null;
  constructor() { Events.current = this; }
  addEventListener(name: string, handler: (event: MessageEvent) => void) { this.handlers.set(name, handler); }
  emit(name: string, data: object) { act(() => { this.handlers.get(name)?.(new MessageEvent(name, { data: JSON.stringify(data) })); }); }
}
const fetchMock = vi.fn();
beforeEach(() => { vi.stubGlobal('EventSource', Events); vi.stubGlobal('fetch', fetchMock); fetchMock.mockReset(); Events.current = undefined as unknown as Events; fetchMock.mockResolvedValue({ ok: true, json: async () => ({ id: 'job-1' }) }); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
async function generate() { fireEvent.change(screen.getByLabelText('Model description'), { target: { value: 'cube 10 mm' } }); fireEvent.click(screen.getByText('Generate model ↗')); await waitFor(() => expect(fetchMock).toHaveBeenCalled()); await waitFor(() => expect(Events.current || screen.queryByRole('alert')).toBeTruthy()); }
describe('dashboard', () => {
  it('generates, streams source, renders edits, and preserves a successful preview after failure', async () => {
    render(<Dashboard />); await generate();
    await vi.waitFor(() => { Events.current.emit('source', { source: 'cube(10);', attempt: 1 }); Events.current.emit('complete', { id: 'job-1', status: 'succeeded', attempt: 1, diagnostics: '', artifactAvailable: true }); });
    await waitFor(() => expect(screen.getByTestId('viewer')).toHaveTextContent('job-1/model.stl'));
    fireEvent.change(screen.getByLabelText('OpenSCAD source'), { target: { value: 'cube(20);' } });
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ id: 'job-2' }) });
    fireEvent.click(screen.getByText('Render code ▷'));
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith(expect.any(String), expect.objectContaining({ body: JSON.stringify({ source: 'cube(20);' }) })));
    Events.current.emit('complete', { id: 'job-2', status: 'failed', attempt: 1, diagnostics: 'bad geometry', artifactAvailable: false });
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('bad geometry'));
    expect(screen.getByTestId('viewer')).toHaveTextContent('job-1/model.stl');
  });
  it('shows submission errors', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 409 }); render(<Dashboard />); await generate();
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Another job is running'));
  });
  it('cancels an active job', async () => {
    render(<Dashboard />); await generate(); Events.current.emit('status', { id: 'job-1', status: 'generating', attempt: 1 });
    await waitFor(() => expect(screen.getByText('Cancel job')).toBeEnabled()); fireEvent.click(screen.getByText('Cancel job'));
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith(expect.stringContaining('/job-1'), { method: 'DELETE' }));
    Events.current.emit('complete', { id: 'job-1', status: 'cancelled', attempt: 1, diagnostics: 'cancelled', artifactAvailable: false });
    await waitFor(() => expect(screen.getByText('Generate model ↗')).toBeEnabled());
  });
});
