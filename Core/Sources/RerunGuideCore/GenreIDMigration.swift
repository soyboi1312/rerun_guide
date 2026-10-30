import Foundation

/// A genre shelf is one channel (`genre:G`) until both its films and its episodes reach the minimum, then two
/// (`genre:G:movies`, `genre:G:tv`), and one again if a half shrinks. The ids change, and every per-channel choice is
/// keyed by id, so carry them across: a hidden flag goes to every new channel (a Kids profile must not suddenly see
/// them), an edit goes to the new channel with the most items, and the stale ids are dropped.
public enum GenreIDMigration {
    /// `currentIDs`: this rebuild's automatic channel ids. `sizes`: item counts by id. `hidden`: each profile's hidden
    /// ids. `edits`: per-channel edits. Returns whether anything changed and whether an edit moved (which means the
    /// lineup has to be resolved again for the edit to apply).
    public static func migrate<Edit>(currentIDs: [String], sizes: [String: Int], hidden: inout [Set<String>],
                                     edits: inout [String: Edit]) -> (changed: Bool, editMoved: Bool) {
        var groups: [String: [String]] = [:]   // base id → its current ids
        for id in currentIDs where id.hasPrefix("genre:") {
            let base = id.hasSuffix(":movies") ? String(id.dropLast(7)) : id.hasSuffix(":tv") ? String(id.dropLast(3)) : id
            groups[base, default: []].append(id)
        }
        var changed = false, editMoved = false
        for (base, now) in groups.sorted(by: { $0.key < $1.key }) {
            let stale = (now == [base] ? [base + ":movies", base + ":tv"] : [base]).filter { !now.contains($0) }
            for i in hidden.indices where !hidden[i].isDisjoint(with: stale) {
                hidden[i].subtract(stale); hidden[i].formUnion(now); changed = true
            }
            guard let from = stale.first(where: { edits[$0] != nil }) else { continue }
            if now.allSatisfy({ edits[$0] == nil }),
               let to = now.max(by: { (sizes[$0] ?? 0, $1) < (sizes[$1] ?? 0, $0) }) {
                edits[to] = edits[from]; editMoved = true
            }
            for id in stale { edits[id] = nil }
            changed = true
        }
        return (changed, editMoved)
    }
}
