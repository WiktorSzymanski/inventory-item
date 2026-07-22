# Event Sourcing vs Traditional OL — Performance Discussion

## When can ES have better performance than traditional backend?

- **Write-heavy workloads** — appends only, no read-modify-write cycles
- **High concurrency on distinct aggregates** — each aggregate has its own stream, no cross-stream contention
- **Audit / history queries** — full history is free, no extra audit tables
- **Temporal queries & replays** — rebuild state at any point in time via stream replay
- **Fan-out / projections** — multiple independent projections built concurrently without touching the write path
- Sequential append vs random write — event log appends always at end of log; traditional UPDATE hits random B-tree page

---

## When traditional OL closes the gap

- Single-field updates on small rows — write amplification negligible
- Simple read-by-current-state — no projection hop needed
- Low event count per aggregate — replay cost approaches SELECT cost

---

## Conflict retry cost: ES is NOT cheaper than traditional OL

On `WrongExpectedVersionException`, ES **cannot** just re-append with the new revision. It must:

1. Replay the stream again to reconstruct current aggregate state
2. Re-apply the command against updated state
3. Check invariants (e.g. is there still enough stock?)
4. Only then append — or reject if invariant now fails

This is equivalent to traditional OL re-reading the row. ES replay can actually be **more expensive** if the stream is long (argument for snapshots).

---

## Write amplification point: only valid against ORM whole-object saves

The write amplification advantage (ES appends only the delta; traditional rewrites the full row) only holds if the traditional system does **ORM-style whole-object saves** (Hibernate/JPA default `save()`).

Against hand-written targeted SQL (`UPDATE inventory SET quantity = quantity - 1`), the advantage disappears.

### Rich aggregate example is also flawed

Example: `Order` with 40+ fields, updating only shipping address.

- Traditional OL rewrites 4 KB row → ES wins
- BUT: good normalized design would have `order_addresses` as a separate table — then updating shipping address is a targeted UPDATE on a tiny row, equivalent to the ES append

---

## What genuinely remains after all corrections

Against a **well-designed, properly normalized, hand-written SQL + OL** traditional system:

| Advantage | Survives? |
|---|---|
| Sequential I/O (append vs random write) | Yes |
| No index churn on source of truth (event log has one index) | Yes |
| Projection decoupling (write latency excludes read model update) | Yes |
| Write amplification | No — requires ORM or denormalized schema |
| Conflict retry cheaper | No — both must reload state |
| Single operation vs two on happy path | Debatable — both need to read state to validate command |

---

## Can Outbox pattern close the gap with ES on architectural benefits?

**Outbox gives you:**
- Event-driven integration (reliable event publishing)
- Audit log (outbox table records what happened)

**What ES still has that Outbox doesn't:**
- **True temporal queries** — outbox is a relay buffer, typically deleted after delivery; retaining it permanently means you've essentially built a read-only event store anyway
- **Aggregate reconstruction from history** — ES event log is the source of truth; Outbox + traditional OL has the row as source of truth
- **Projection rebuild from scratch** — replayability from the beginning
- **No dual-write problem** — in ES the event *is* the write; Outbox still requires atomic write to both row and outbox table

---

## Honest conclusion

Against a well-designed, properly normalized, hand-written SQL + OL traditional system, **ES has no meaningful write performance advantage**.

The ES value proposition is **architectural**:
- Replayability
- Projection rebuild
- Full permanent history as first-class citizen
- Event-driven integration without dual-write

The more interesting thesis question is: **when does the architectural complexity of ES pay off over Outbox + traditional OL?**
