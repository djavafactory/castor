const TOKENS = [
  ['canvas', 'bg-canvas'],
  ['paper', 'bg-paper'],
  ['paper-2', 'bg-paper-2'],
  ['ink', 'bg-ink'],
  ['muted', 'bg-muted'],
  ['line-soft', 'bg-line-soft'],
  ['signal', 'bg-signal'],
  ['bad', 'bg-bad'],
] as const

/** Local and dev only: the colour tokens the app is built from. */
export function DesignSystemPage() {
  return (
    <main className="h-full overflow-auto bg-canvas p-8">
      <h1 className="text-xl font-bold">Design system</h1>
      <ul className="mt-4 grid grid-cols-4 gap-3">
        {TOKENS.map(([name, cls]) => (
          <li key={name} className="rounded-lg bg-paper p-2 text-xs shadow-card">
            <div className={`h-10 rounded ${cls} ring-1 ring-line`} />
            <p className="mt-1.5 font-mono">{name}</p>
          </li>
        ))}
      </ul>
    </main>
  )
}
