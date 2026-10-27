import { TilesNote } from '@/components/states/states'
import { useUiStore } from '@/lib/stores/ui-store'
import { versionLine } from '@/lib/version'
import { shortcutsFor } from './shortcuts'

export const ATTRIBUTION =
  'Imagery: Sentinel-2 cloudless by EOX IT Services GmbH (CC BY-NC-SA 4.0), modified Copernicus Sentinel data 2024. Outlines: (c) OpenStreetMap contributors (ODbL).'

export const SYNTHETIC_EXPLANATION =
  'Synthetic layout: positions are placeholders, not a survey of Westports'

const ACCURACY_SWATCHES = [
  { code: 'SURVEYED', label: 'Surveyed', className: 'bg-acc-surveyed' },
  { code: 'VERIFIED', label: 'Imported verified', className: 'bg-acc-verified' },
  { code: 'OPERATOR', label: 'Operator-entered', className: 'bg-acc-operator' },
  { code: 'APPROXIMATE', label: 'Approximate', className: 'bg-acc-approx' },
] as const

function Heading({ children }: { children: string }) {
  return (
    <h4 className="mt-3 mb-1.5 text-[11px] font-bold tracking-[0.07em] text-muted uppercase first:mt-0">
      {children}
    </h4>
  )
}

/** Legend, accuracy labels, keyboard table and attribution. Scrolls inside its popover. */
export function HelpContent({ serverVersion }: { serverVersion?: string }) {
  const mode = useUiStore((s) => s.mode)
  const imageryFailed = useUiStore((s) => s.imageryFailed && s.basemap === 'sentinel2')
  return (
    <div className="flex flex-col">
      <Heading>Accuracy of shapes</Heading>
      <ul className="flex flex-col gap-1">
        {ACCURACY_SWATCHES.map((s) => (
          <li key={s.code} className="flex items-center gap-2">
            <span aria-hidden="true" className={`size-3 rounded-sm ${s.className}`} />
            {s.label}
          </li>
        ))}
      </ul>
      <Heading>Synthetic</Heading>
      <p className="text-xs text-muted">{SYNTHETIC_EXPLANATION}</p>
      <Heading>Keyboard</Heading>
      <table className="w-full text-xs">
        <tbody>
          {shortcutsFor(mode).map((s) => (
            <tr key={s.keys} className="border-t border-line-soft first:border-0">
              <th
                scope="row"
                className="py-1 pr-2 text-left font-mono font-semibold whitespace-nowrap"
              >
                {s.keys}
              </th>
              <td className="py-1 text-muted">{s.action}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Heading>Attribution</Heading>
      <p className="text-xs text-muted">{ATTRIBUTION}</p>
      <p className="mt-2 text-xs text-muted">{versionLine(serverVersion)}</p>
      {imageryFailed && <TilesNote />}
    </div>
  )
}
