import { Box3 } from 'three'
import { envelopeSize, fitsEnvelope, MIN_ENVELOPE_HEIGHT } from './envelope'
import type { ModelSize } from './model-spec'
import { MODEL_SPECS, buildModel, modelScale } from './registry'

const SCALES = [1, 0.6, 1.8]

describe('envelope', () => {
  it('is never flatter than 0.3 m', () => {
    expect(envelopeSize(undefined, { length: 10, width: 5, height: 0 }).height).toBe(
      MIN_ENVELOPE_HEIGHT,
    )
    expect(envelopeSize(undefined, { length: 10, width: 5, height: 4 }).height).toBe(4)
  })

  describe.each(MODEL_SPECS.map((s) => [s.key, s] as const))('%s', (_key, spec) => {
    it.each(SCALES)('stays inside its envelope at %sx its default size', (k) => {
      const size: ModelSize = {
        length: spec.size.length * k,
        width: spec.size.width * k,
        height: spec.size.height * k,
      }
      const built = buildModel(
        spec,
        spec.parametric ? { size, details: spec.details ?? {} } : undefined,
      )
      if (!spec.parametric) built.group.scale.set(...modelScale(spec, size))
      built.group.updateMatrixWorld(true)
      const drawn = new Box3().setFromObject(built.group)
      expect(
        fitsEnvelope(drawn, envelopeSize(spec, size)),
        `${spec.key} drawn ${JSON.stringify(drawn)} outside ${JSON.stringify(envelopeSize(spec, size))}`,
      ).toBe(true)
    })
  })
})
