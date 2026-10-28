import { useEffect, useState } from 'react'
import { webglAvailable } from '../webgl'
import { CATEGORY_LABELS, MODEL_SPECS } from './registry'
import type { ModelCategory } from './model-spec'
import { scheduleThumbnail } from './thumbnails'

const CATEGORIES = Object.keys(CATEGORY_LABELS) as ModelCategory[]

/** Thumbnail of one model, shown at 84 x 60 (rendered at twice that). Blank where WebGL is missing. */
export function ModelThumbnail({ modelKey }: { modelKey: string }) {
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    if (!webglAvailable()) return
    // Rendered after the palette paints, three per frame, so opening Design does not stall
    return scheduleThumbnail(modelKey, setSrc)
  }, [modelKey])

  return src ? (
    <img src={src} alt="" width={84} height={60} className="h-[60px] w-[84px] shrink-0" />
  ) : (
    <span aria-hidden="true" className="h-[60px] w-[84px] shrink-0 rounded-md bg-paper-2" />
  )
}

/** The model library as the palette lists it: every crane, handling machine and vehicle with its thumbnail. */
export function ModelPalette() {
  return (
    <div className="mt-3 flex flex-col gap-4" data-testid="model-palette">
      {CATEGORIES.map((category) => (
        <section key={category} aria-label={CATEGORY_LABELS[category]}>
          <h3 className="mb-1.5 text-xs font-semibold text-muted">{CATEGORY_LABELS[category]}</h3>
          <ul className="flex flex-col gap-1.5">
            {MODEL_SPECS.filter((spec) => spec.category === category).map((spec) => (
              <li
                key={spec.key}
                data-model-key={spec.key}
                className="flex items-center gap-2.5 rounded-lg border border-line-soft p-1.5"
              >
                <ModelThumbnail modelKey={spec.key} />
                <span className="min-w-0">
                  <span className="block truncate text-[13px] font-medium text-ink">
                    {spec.label}
                  </span>
                  <span className="block font-mono text-[11px] text-muted">
                    {spec.size.length} x {spec.size.width} m
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
