package pl.szymanski.wiktor.config

/**
 * The five collections this branch stores state in.
 *
 * The names deliberately mirror the TABLE names the Postgres branches use
 * (`V6__axon_tables.sql` on ES-2, `V1__init.sql` on ES-4) rather than the Axon MongoDB
 * extension's own defaults (`domainevents`, `snapshotevents`, `trackingtokens`, `sagas`).
 *
 * That is not cosmetic. The benchmark's per-relation panel is "Live rows by table",
 * `pg_stat_user_tables_n_live_tup{relname=...}`; its Mongo counterpart is
 * `mongodb_collstats_storageStats_count{collection=...}`. Keeping the names equal is what
 * lets an ES-2 run and an ES-2-mongo run be read side by side on the same row of a table
 * instead of through a translation key.
 *
 * `association_value_entry` has no entry here on purpose: [MongoSagaStore] keeps a saga's
 * association values as an array INSIDE the saga document, so the second table the JDBC saga
 * store needs does not exist. That is a genuine structural difference, not an omission.
 */
object MongoCollections {
    const val DOMAIN_EVENTS = "domain_event_entry"
    const val SNAPSHOT_EVENTS = "snapshot_event_entry"
    const val TRACKING_TOKENS = "token_entry"
    const val SAGAS = "saga_entry"

    /** Unused -- no branch registers a dead-letter queue -- but the template must name it. */
    const val DEAD_LETTERS = "dead_letter_entry"
}
