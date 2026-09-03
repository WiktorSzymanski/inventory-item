package pl.szymanski.wiktor.config

import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoCommandException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import org.assertj.core.api.Assertions.assertThat
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.junit.jupiter.api.Test

/**
 * The contract [MongoConflictResolver] has to hold, asserted without a database.
 *
 * What this is really guarding is a SILENT failure. With `NullLockFactory` on the InventoryItem
 * repository, a conflict this resolver fails to recognise is not reported as a
 * `ConcurrencyException`, [ConcurrencyRetryScheduler] therefore refuses to retry it, and the
 * command fails terminally into the saga's abandon() path. The run then shows a high rejection
 * rate and normal-looking latency -- no errors, no exceptions in the log, and a completely
 * invalid measurement. There is no assertion anywhere else in the suite that would catch it.
 */
class MongoConflictResolverTest {

    private val address = ServerAddress("localhost", 27017)

    @Test
    fun `a bulk duplicate key is a conflict`() {
        // The shape the event store actually produces: appendEvents uses insertMany, so a
        // second command appending the same {aggregateIdentifier, sequenceNumber} comes back
        // as a bulk write error, never as a single-document one.
        val e = MongoBulkWriteException(
            BulkWriteResult.unacknowledged(),
            listOf(BulkWriteError(11000, "E11000 duplicate key", BsonDocument(), 0)),
            null,
            address,
            emptySet(),
        )
        assertThat(MongoConflictResolver.isDuplicateKeyViolation(e)).isTrue()
    }

    @Test
    fun `a single-document duplicate key is a conflict`() {
        val e = MongoWriteException(WriteError(11000, "duplicate key", BsonDocument()), address)
        assertThat(MongoConflictResolver.isDuplicateKeyViolation(e)).isTrue()
    }

    @Test
    fun `a write conflict is a conflict`() {
        // Reachable ONLY because appends run inside a transaction. Two sessions touching the
        // same document make the server abort one with 112 rather than letting it reach the
        // unique index, so a resolver that matched 11000 alone would call this terminal --
        // which is the exact bug this test exists to prevent.
        assertThat(MongoConflictResolver.isDuplicateKeyViolation(commandException(112, "WriteConflict")))
            .isTrue()
    }

    @Test
    fun `a transient transaction error is a conflict whatever its code`() {
        val response = BsonDocument()
            .append("ok", BsonInt32(0))
            .append("code", BsonInt32(251))
            .append("errmsg", BsonString("transaction aborted"))
            .append("errorLabels", org.bson.BsonArray(listOf(BsonString("TransientTransactionError"))))
        assertThat(MongoConflictResolver.isDuplicateKeyViolation(MongoCommandException(response, address)))
            .isTrue()
    }

    @Test
    fun `a conflict wrapped by another exception is still a conflict`() {
        // Axon, Spring and the driver all wrap. Testing only the top-level exception would let
        // a real conflict through as terminal.
        val wrapped = RuntimeException("append failed", IllegalStateException(commandException(112, "WriteConflict")))
        assertThat(MongoConflictResolver.isDuplicateKeyViolation(wrapped)).isTrue()
    }

    @Test
    fun `an unrelated failure is not a conflict`() {
        // The negative case matters as much as the positives: treating a genuine failure as a
        // conflict would make ConcurrencyRetryScheduler retry something that can never succeed,
        // five times, holding a connection each time.
        assertThat(MongoConflictResolver.isDuplicateKeyViolation(commandException(13, "Unauthorized")))
            .isFalse()
        assertThat(MongoConflictResolver.isDuplicateKeyViolation(IllegalArgumentException("nope")))
            .isFalse()
    }

    private fun commandException(code: Int, name: String): MongoCommandException {
        val response = BsonDocument()
            .append("ok", BsonInt32(0))
            .append("code", BsonInt32(code))
            .append("codeName", BsonString(name))
            .append("errmsg", BsonString(name))
        return MongoCommandException(response, address)
    }
}
