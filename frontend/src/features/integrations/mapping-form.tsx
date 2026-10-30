import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input, Label, Select } from '@/components/ui/field'
import { Drawer } from '@/components/ui/drawer'
import {
  MAPPING_ENTITY_TYPES,
  MAPPING_SOURCES,
  useCreateIdentityMapping,
  type MappingEntityType,
  type MappingSource,
} from '@/lib/api'
import { utcInstant } from './integrations-text'
import { problemText } from './problem-text'

/** What the form starts with: empty, or the source id a quarantined record is waiting for. */
export interface MappingDraft {
  source: string
  entityType: string
  sourceId: string
}

const isSource = (value: string): value is MappingSource =>
  (MAPPING_SOURCES as readonly string[]).includes(value)
const isType = (value: string): value is MappingEntityType =>
  (MAPPING_ENTITY_TYPES as readonly string[]).includes(value)

/**
 * Maps a source's id for an asset or call to the canonical id Castor uses, from a date (blank: from the beginning, so
 * records already quarantined map too) to an optional end date. Times are UTC. Overlapping an existing mapping of the
 * same id is refused by the server with its reason.
 */
export function MappingForm({
  draft,
  onClose,
  onCreated,
}: {
  draft: MappingDraft | null
  onClose: () => void
  /** Called with the source id after the mapping is saved. */
  onCreated: (sourceId: string) => void
}) {
  const create = useCreateIdentityMapping()
  const [source, setSource] = useState(draft && isSource(draft.source) ? draft.source : 'cbas')
  const [entityType, setEntityType] = useState(
    draft && isType(draft.entityType) ? draft.entityType : 'BERTH',
  )
  const [sourceId, setSourceId] = useState(draft?.sourceId ?? '')
  const [canonicalId, setCanonicalId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [error, setError] = useState<string | null>(null)

  function submit(event: FormEvent) {
    event.preventDefault()
    const effectiveFrom = utcInstant(from)
    const effectiveTo = utcInstant(to)
    if (effectiveFrom === null || effectiveTo === null) {
      setError('Enter the dates as year-month-day and time.')
      return
    }
    if (sourceId.trim() === '' || canonicalId.trim() === '') {
      setError('The source id and the canonical id are required.')
      return
    }
    if (effectiveFrom && effectiveTo && effectiveTo <= effectiveFrom) {
      setError('The end must be after the start.')
      return
    }
    setError(null)
    create.mutate(
      {
        sourceSystem: source,
        sourceEntityType: entityType,
        sourceId: sourceId.trim(),
        canonicalId: canonicalId.trim(),
        effectiveFrom,
        effectiveTo,
      },
      {
        onSuccess: (mapping) => {
          toast(`Mapping added for ${mapping.sourceId}`)
          onCreated(mapping.sourceId)
        },
        onError: (e) => setError(problemText(e)),
      },
    )
  }

  return (
    <Drawer open title="New identity mapping" onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-3" noValidate>
        <p className="text-xs text-muted">
          Says which Castor asset a source id stands for. Records already refused for this id can
          then be replayed.
        </p>
        <div className="flex flex-col gap-1">
          <Label htmlFor="mapping-source">Source system</Label>
          <Select
            id="mapping-source"
            value={source}
            onChange={(e) => isSource(e.target.value) && setSource(e.target.value)}
          >
            {MAPPING_SOURCES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="mapping-type">Kind of thing</Label>
          <Select
            id="mapping-type"
            value={entityType}
            onChange={(e) => isType(e.target.value) && setEntityType(e.target.value)}
          >
            {MAPPING_ENTITY_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="mapping-source-id">Source id</Label>
          <Input
            id="mapping-source-id"
            value={sourceId}
            placeholder="What the source calls it, for example B3"
            onChange={(e) => setSourceId(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="mapping-canonical-id">Canonical id</Label>
          <Input
            id="mapping-canonical-id"
            value={canonicalId}
            placeholder="The id of the asset in the port layout"
            onChange={(e) => setCanonicalId(e.target.value)}
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="flex flex-col gap-1">
            <Label htmlFor="mapping-from">Valid from (UTC)</Label>
            <Input
              id="mapping-from"
              type="datetime-local"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
            <span className="text-[11px] text-muted">Blank: from the beginning</span>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="mapping-to">Valid until (UTC)</Label>
            <Input
              id="mapping-to"
              type="datetime-local"
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
            <span className="text-[11px] text-muted">Blank: no end</span>
          </div>
        </div>
        {error && (
          <p role="alert" className="text-xs text-bad">
            {error}
          </p>
        )}
        <div className="flex gap-2">
          <Button type="submit" variant="primary" disabled={create.isPending}>
            Add mapping
          </Button>
          <Button onClick={onClose}>Cancel</Button>
        </div>
      </form>
    </Drawer>
  )
}
