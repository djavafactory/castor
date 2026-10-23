/** 24 px ink square (radius 7) with the signal square inset 6 px (radius 3). */
export function Logo() {
  return (
    <span aria-hidden="true" className="relative inline-block size-6 shrink-0 rounded-[7px] bg-ink">
      <span className="absolute inset-1.5 rounded-[3px] bg-signal" />
    </span>
  )
}
