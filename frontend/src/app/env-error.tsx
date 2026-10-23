export function EnvError({ issues }: { issues: string[] }) {
  return (
    <main role="alert" className="grid min-h-screen place-items-center p-6">
      <div className="max-w-xl">
        <h1 className="text-xl font-semibold">Castor cannot start: invalid configuration</h1>
        <p className="mt-2">Fix these environment values and reload the page:</p>
        <ul className="mt-3 list-disc pl-6 font-mono text-sm">
          {issues.map((issue) => (
            <li key={issue}>{issue}</li>
          ))}
        </ul>
      </div>
    </main>
  )
}
