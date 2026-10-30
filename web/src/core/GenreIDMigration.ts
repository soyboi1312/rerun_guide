// Port of android/core GenreIDMigration.kt.
import { stableSort } from './Lang'
import { swiftCompare } from './Models'

/**
 * A genre is one channel (`genre:G`) until both its films and its episodes reach the minimum, then two
 * (`genre:G:movies`, `genre:G:tv`), and one again if a half shrinks. The ids change, and every per-channel choice is
 * keyed by id, so carry them across: a hidden flag goes to every new channel (a Kids profile must not suddenly see
 * them), an edit goes to the new channel with the most items, and the stale ids are dropped.
 */
export const GenreIDMigration = {
  /**
   * `currentIDs`: this rebuild's automatic channel ids. `sizes`: item counts by id. `hidden`: each profile's hidden
   * ids (Swift `inout`: its entries are replaced in place). `edits`: per-channel edits (updated in place). Returns
   * whether anything changed and whether an edit moved (which means the lineup has to be resolved again for the edit
   * to apply).
   */
  migrate<Edit>(currentIDs: readonly string[], sizes: ReadonlyMap<string, number>, hidden: Set<string>[],
                edits: Map<string, Edit>): GenreIDMigration.Result {
    const groups = new Map<string, string[]>()   // base id → its current ids
    for (const id of currentIDs) {
      if (!id.startsWith('genre:')) continue
      const base = id.endsWith(':movies') ? id.slice(0, -7) : id.endsWith(':tv') ? id.slice(0, -3) : id
      const g = groups.get(base)
      if (g) g.push(id)
      else groups.set(base, [id])
    }
    let changed = false
    let editMoved = false
    const entries: [string, string[]][] = []
    groups.forEach((ids, base) => entries.push([base, ids]))
    for (const [base, now] of stableSort(entries, (a, b) => swiftCompare(a[0], b[0]))) {
      const stale = (now.length === 1 && now[0] === base ? [`${base}:movies`, `${base}:tv`] : [base]).filter(it => now.indexOf(it) < 0)
      for (let i = 0; i < hidden.length; i++) {
        if (!stale.some(it => hidden[i].has(it))) continue
        const next = new Set<string>()
        hidden[i].forEach(it => { if (stale.indexOf(it) < 0) next.add(it) })
        for (const it of now) next.add(it)
        hidden[i] = next; changed = true
      }
      const from = stale.find(it => edits.has(it))
      if (from == null) continue
      if (now.every(it => !edits.has(it))) {
        // The biggest current channel; on a tie, the smaller id.
        let to: string | null = null
        for (const it of now) {
          if (to == null) { to = it; continue }
          const c = (sizes.get(to) ?? 0) - (sizes.get(it) ?? 0) || swiftCompare(it, to)
          if (c < 0) to = it
        }
        if (to != null) { edits.set(to, edits.get(from)!); editMoved = true }
      }
      for (const id of stale) edits.delete(id)
      changed = true
    }
    return { changed, editMoved }
  },
}
export namespace GenreIDMigration {
  /** Swift's `(changed: Bool, editMoved: Bool)`. */
  export interface Result {
    readonly changed: boolean
    readonly editMoved: boolean
  }
}
