import { TileCache, loadTileImage } from '../scene/stage/tiles'

/** Most decoded tiles kept for the map; the oldest go first. */
const READY_LIMIT = 500

/**
 * The satellite tiles of the 2D map. A tile that is asked for and not there yet is loaded in the background and
 * the listeners run when one arrives, so the map can paint again. A tile that fails stays absent: the land colour shows.
 */
export class MapTiles {
  private readonly listeners = new Set<() => void>()
  private readonly cache = new TileCache(loadTileImage)
  private readonly ready = new Map<string, CanvasImageSource | null>()
  private readonly pending = new Set<string>()

  /** Calls `listener` whenever a tile arrives; returns the way to stop. */
  listen(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  /** The decoded tile, or null while it loads (or when it failed). */
  get = (url: string): CanvasImageSource | null => {
    if (this.ready.has(url)) return this.ready.get(url) ?? null
    if (!this.pending.has(url)) {
      this.pending.add(url)
      void this.cache.get(url).then((image) => {
        this.pending.delete(url)
        this.ready.set(url, image)
        if (this.ready.size > READY_LIMIT) {
          const oldest = this.ready.keys().next().value
          if (oldest !== undefined) this.ready.delete(oldest)
        }
        if (image) this.listeners.forEach((listener) => listener())
      })
    }
    return null
  }
}
