package pl.szymanski.wiktor.config

import com.mongodb.client.MongoCollection
import org.axonframework.extensions.mongo.MongoTemplate
import org.bson.Document
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoDatabaseUtils

/**
 * Axon's [MongoTemplate] is a five-method interface handing the stores their collections. Both
 * implementations here resolve the same four collection names; they differ ONLY in whether the
 * returned collection participates in the Spring-managed MongoDB session.
 *
 * The extension ships its own `SpringMongoTemplate`, but only inside
 * `axon-mongo-spring-boot-starter`. That module is compiled against Spring Data MongoDB 4.x
 * while this branch resolves 5.0.5 through the Spring Boot 4.0.6 BOM, so it is not used; the
 * plain `axon-mongo` artifact depends on `mongodb-driver-sync` alone. Reimplementing the
 * interface costs ten lines and removes the version coupling entirely.
 */
class SessionAwareMongoTemplate(private val factory: MongoDatabaseFactory) : MongoTemplate {

    /**
     * [MongoDatabaseUtils.getDatabase] returns a session-bound database whenever a Spring
     * MongoDB transaction is active on this thread, and a plain one otherwise. That is what
     * makes an Axon append, its token update and its saga write land in ONE MongoDB
     * transaction -- the property the single-node replica set exists to provide.
     */
    private fun collection(name: String): MongoCollection<Document> =
        MongoDatabaseUtils.getDatabase(factory).getCollection(name)

    override fun eventCollection(): MongoCollection<Document> = collection(MongoCollections.DOMAIN_EVENTS)
    override fun snapshotCollection(): MongoCollection<Document> = collection(MongoCollections.SNAPSHOT_EVENTS)
    override fun trackingTokensCollection(): MongoCollection<Document> = collection(MongoCollections.TRACKING_TOKENS)
    override fun sagaCollection(): MongoCollection<Document> = collection(MongoCollections.SAGAS)
    override fun deadLetterCollection(): MongoCollection<Document> = collection(MongoCollections.DEAD_LETTERS)
}

/**
 * The same four collections, resolved WITHOUT the session.
 *
 * This exists for exactly one caller, [MongoIndexInitializer], and the reason is a hard
 * MongoDB rule rather than a preference: `createIndexes` may not run inside a multi-document
 * transaction against a collection that already exists. Both
 * `MongoEventStorageEngine.ensureIndexes()` and `MongoTokenStore`'s constructor-time index
 * creation route through the configured Axon `TransactionManager`, so with a real
 * transaction manager wired they fail on the SECOND startup -- when the collections are
 * already there. Creating the indexes through this template instead keeps them out of any
 * transaction.
 */
class DirectMongoTemplate(private val factory: MongoDatabaseFactory) : MongoTemplate {

    private fun collection(name: String): MongoCollection<Document> =
        factory.mongoDatabase.getCollection(name)

    override fun eventCollection(): MongoCollection<Document> = collection(MongoCollections.DOMAIN_EVENTS)
    override fun snapshotCollection(): MongoCollection<Document> = collection(MongoCollections.SNAPSHOT_EVENTS)
    override fun trackingTokensCollection(): MongoCollection<Document> = collection(MongoCollections.TRACKING_TOKENS)
    override fun sagaCollection(): MongoCollection<Document> = collection(MongoCollections.SAGAS)
    override fun deadLetterCollection(): MongoCollection<Document> = collection(MongoCollections.DEAD_LETTERS)
}
