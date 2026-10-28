import { useId, useState, type KeyboardEvent, type ReactNode } from 'react'
import { toast } from 'sonner'
import { Input, Select } from '@/components/ui/field'
import { formatNumber, parseNumber, type NumberSpec } from './field-values'

const AREA =
  'w-full rounded-md border border-line bg-white px-2 py-[5px] text-[13px] text-ink placeholder:text-muted disabled:opacity-40 aria-[invalid=true]:border-bad'

/** One label and its control, or read-only value: a 118 px label column, the control fills the rest. */
export function FieldRow({
  label,
  htmlFor,
  children,
}: {
  label: string
  htmlFor?: string
  children: ReactNode
}) {
  return (
    <div className="grid grid-cols-[118px_1fr] items-center gap-x-2 gap-y-1.5">
      {htmlFor ? (
        <label htmlFor={htmlFor} className="text-xs text-muted">
          {label}
        </label>
      ) : (
        <span className="text-xs text-muted">{label}</span>
      )}
      <div className="min-w-0">{children}</div>
    </div>
  )
}

/** A value the editor cannot change here: shown in the monospace figures of the panel. */
export function ReadOnlyRow({ label, value }: { label: string; value: string }) {
  return (
    <FieldRow label={label}>
      <p className="m-0 font-mono text-xs break-words text-ink">{value}</p>
    </FieldRow>
  )
}

interface NumberFieldProps {
  label: string
  value: number | null | undefined
  spec: NumberSpec
  unit?: string
  /** Digits kept in the text; the stored value keeps up to 3 decimals (7 for coordinates). */
  decimals?: number
  disabled?: boolean
  /** A cell of a table: the input only, named by its label. */
  bare?: boolean
  /** Saves the value; resolves to false when the value was refused or not saved, so the field shows the stored one. */
  onCommit: (value: number) => Promise<boolean>
}

/**
 * A number of the Inspector: it commits on blur or Enter, only when the editor has typed something. Invalid input keeps
 * its text, shows a red outline and says why in a toast; Escape puts the stored value back.
 */
export function NumberField({
  label,
  value,
  spec,
  unit,
  decimals = 3,
  disabled,
  bare,
  onCommit,
}: NumberFieldProps) {
  const id = useId()
  const shown = formatNumber(value, decimals)
  const [text, setText] = useState(shown)
  const [seen, setSeen] = useState(shown)
  const [dirty, setDirty] = useState(false)
  const [invalid, setInvalid] = useState(false)
  // the stored value changed (saved, undone or reloaded): the field follows it
  if (shown !== seen) {
    setSeen(shown)
    setText(shown)
    setDirty(false)
    setInvalid(false)
  }

  async function commit() {
    if (!dirty) return
    setDirty(false)
    const parsed = parseNumber(text, spec)
    if (!parsed.ok) {
      setInvalid(true)
      toast(parsed.message)
      return
    }
    setInvalid(false)
    if (value !== null && value !== undefined && Math.abs(parsed.value - value) < 1e-9) return
    const kept = await onCommit(parsed.value)
    if (!kept) setText(shown)
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault()
      void commit()
    } else if (event.key === 'Escape') {
      setText(shown)
      setDirty(false)
      setInvalid(false)
    }
  }

  const input = (
    <Input
      id={id}
      type="number"
      inputMode="decimal"
      step={spec.integer ? 1 : 'any'}
      aria-label={bare ? label : undefined}
      value={text}
      disabled={disabled}
      aria-invalid={invalid || undefined}
      onChange={(event) => {
        setText(event.target.value)
        setDirty(true)
      }}
      onBlur={() => void commit()}
      onKeyDown={onKeyDown}
    />
  )
  if (bare) return input
  return (
    <FieldRow label={label} htmlFor={id}>
      <div className="flex items-center gap-1.5">
        {input}
        {unit && <span className="shrink-0 text-xs text-muted">{unit}</span>}
      </div>
    </FieldRow>
  )
}

interface TextFieldProps {
  label: string
  value: string | null | undefined
  disabled?: boolean
  multiline?: boolean
  maxLength?: number
  /** A name is required: an empty text is refused with a toast. */
  required?: boolean
  onCommit: (text: string) => Promise<boolean>
}

/** Text of the Inspector; it commits on blur or Enter (a note on blur only, Enter makes a new line). */
export function TextField({
  label,
  value,
  disabled,
  multiline,
  maxLength,
  required,
  onCommit,
}: TextFieldProps) {
  const id = useId()
  const shown = value ?? ''
  const [text, setText] = useState(shown)
  const [seen, setSeen] = useState(shown)
  const [dirty, setDirty] = useState(false)
  if (shown !== seen) {
    setSeen(shown)
    setText(shown)
    setDirty(false)
  }

  async function commit() {
    if (!dirty) return
    setDirty(false)
    const next = text.trim()
    if (next === shown.trim()) return
    if (required && next === '') {
      toast(`${label}: enter a value`)
      setText(shown)
      return
    }
    const kept = await onCommit(next)
    if (!kept) setText(shown)
  }

  const common = {
    id,
    value: text,
    disabled,
    maxLength,
    onChange: (event: { target: { value: string } }) => {
      setText(event.target.value)
      setDirty(true)
    },
    onBlur: () => void commit(),
  }

  return (
    <FieldRow label={label} htmlFor={id}>
      {multiline ? (
        <textarea rows={2} className={AREA} {...common} />
      ) : (
        <Input
          {...common}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              void commit()
            }
          }}
        />
      )}
    </FieldRow>
  )
}

export interface Choice {
  value: string
  label: string
}

interface SelectFieldProps {
  label: string
  value: string | null | undefined
  options: readonly Choice[]
  /** Text of the empty choice; without it the field always has a value. */
  emptyLabel?: string
  /** A colour dot next to the select (accuracy classes). */
  dot?: string
  disabled?: boolean
  onCommit: (value: string | null) => Promise<boolean>
}

/** A choice of the Inspector: it commits at once when the editor picks an option. */
export function SelectField({
  label,
  value,
  options,
  emptyLabel,
  dot,
  disabled,
  onCommit,
}: SelectFieldProps) {
  const id = useId()
  const current = value ?? ''
  return (
    <FieldRow label={label} htmlFor={id}>
      <div className="flex items-center gap-1.5">
        {dot && (
          <span
            aria-hidden="true"
            className="inline-block size-2 shrink-0 rounded-full"
            style={{ backgroundColor: dot }}
          />
        )}
        <Select
          id={id}
          value={current}
          disabled={disabled}
          onChange={(event) => {
            const next = event.target.value === '' ? null : event.target.value
            if (next === (value ?? null)) return
            void onCommit(next)
          }}
        >
          {(emptyLabel !== undefined || current === '') && (
            <option value="">{emptyLabel ?? '—'}</option>
          )}
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </Select>
      </div>
    </FieldRow>
  )
}

interface CheckListProps {
  label: string
  options: readonly Choice[]
  selected: readonly string[]
  disabled?: boolean
  onCommit: (next: string[]) => Promise<boolean>
}

/** Several choices of the Inspector (allowed cargo, allowed types): one checkbox each, each change commits at once. */
export function CheckList({ label, options, selected, disabled, onCommit }: CheckListProps) {
  return (
    <fieldset className="min-w-0 border-0 p-0">
      <legend className="mb-1 text-xs text-muted">{label}</legend>
      <div className="grid max-h-40 gap-1 overflow-auto pr-1">
        {options.map((o) => {
          const on = selected.includes(o.value)
          return (
            <label key={o.value} className="flex items-center gap-2 text-[13px] text-ink">
              <input
                type="checkbox"
                className="size-3.5 accent-ink"
                checked={on}
                disabled={disabled}
                onChange={() =>
                  void onCommit(on ? selected.filter((s) => s !== o.value) : [...selected, o.value])
                }
              />
              {o.label}
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
