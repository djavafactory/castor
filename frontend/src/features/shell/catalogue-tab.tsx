import { QueryBoundary } from '@/components/states/query-boundary'
import { DataTable, SectionHeading } from '@/components/ui/card'
import { useAssetTypes, useCatalogue } from '@/lib/api'

const GROUP = (n: number) => new Intl.NumberFormat('en-US').format(n)
const size = (s: { lengthM: number | null; widthM: number | null; heightM: number | null }) =>
  [s.lengthM, s.widthM, s.heightM].map((v) => (v == null ? '-' : v)).join(' x ')

/** Cargo types, ship classes and asset types, straight from the catalogue endpoints. */
export function CatalogueTab() {
  const catalogue = useCatalogue()
  const types = useAssetTypes()
  return (
    <QueryBoundary query={catalogue}>
      {(c) => (
        <div>
          <SectionHeading className="mt-0">Cargo types</SectionHeading>
          <DataTable
            caption="Cargo types"
            rowKey={(r) => r.code}
            rows={c.cargoTypes}
            columns={[
              {
                key: 'cargo',
                header: 'Cargo',
                render: (r) => (
                  <span className="flex items-center gap-1.5">
                    <span
                      aria-hidden="true"
                      className="inline-block size-[11px] shrink-0 rounded-sm"
                      style={{ backgroundColor: r.colourHex }}
                    />
                    {r.name}
                  </span>
                ),
              },
              {
                key: 'family',
                header: 'Family',
                render: (r) =>
                  c.cargoFamilies.find((f) => f.code === r.familyCode)?.label ?? r.familyCode,
              },
              { key: 'unit', header: 'Unit', render: (r) => r.baseUnit },
              {
                key: 'density',
                header: 't/m3',
                numeric: true,
                render: (r) => r.densityTM3.toFixed(2),
              },
            ]}
          />
          <p className="mt-1.5 text-xs text-muted">{c.densityNote}</p>
          <SectionHeading>Ship classes</SectionHeading>
          <DataTable
            caption="Ship classes"
            rowKey={(r) => r.code}
            rows={c.shipClasses}
            columns={[
              { key: 'class', header: 'Class', render: (r) => <strong>{r.name}</strong> },
              { key: 'loa', header: 'LOA', numeric: true, render: (r) => r.loaM },
              { key: 'beam', header: 'Beam', numeric: true, render: (r) => r.beamM },
              { key: 'draft', header: 'Draft', numeric: true, render: (r) => r.draftM },
              { key: 'dwt', header: 'DWT (t)', numeric: true, render: (r) => GROUP(r.dwtT) },
            ]}
          />
          <SectionHeading>Asset types</SectionHeading>
          <QueryBoundary query={types}>
            {(list) => (
              <DataTable
                caption="Asset types"
                rowKey={(r) => r.code}
                rows={list}
                columns={[
                  { key: 'type', header: 'Type', render: (r) => r.label },
                  {
                    key: 'group',
                    header: 'Group',
                    render: (r) =>
                      c.paletteGroups.find((g) => g.code === r.paletteGroup)?.label ??
                      r.paletteGroup,
                  },
                  {
                    key: 'size',
                    header: 'Default size (L x W x H m)',
                    render: (r) => size(r.defaults),
                  },
                ]}
              />
            )}
          </QueryBoundary>
        </div>
      )}
    </QueryBoundary>
  )
}
