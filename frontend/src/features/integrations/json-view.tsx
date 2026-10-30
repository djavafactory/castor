/** A JSON value, pretty-printed and read-only. It scrolls inside its box, so a long payload never widens the page. */
export function JsonView({ value, label }: { value: unknown; label: string }) {
  return (
    <pre
      aria-label={label}
      tabIndex={0}
      className="max-h-96 overflow-auto rounded-lg border border-line-soft bg-paper-2 p-3 font-mono text-xs break-words whitespace-pre-wrap"
    >
      {JSON.stringify(value, null, 2)}
    </pre>
  )
}
