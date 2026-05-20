package pl.szymanski.wiktor.config

import com.zaxxer.hikari.HikariDataSource
import org.axonframework.common.jdbc.DataSourceConnectionProvider
import org.axonframework.common.transaction.TransactionManager
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.config.EventProcessingConfigurer
import org.axonframework.eventhandling.tokenstore.jdbc.JdbcTokenStore
import org.axonframework.eventhandling.tokenstore.jdbc.TokenSchema
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition
import org.axonframework.eventsourcing.NoSnapshotTriggerDefinition
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.Snapshotter
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.jdbc.EventSchema
import org.axonframework.eventsourcing.eventstore.jdbc.JdbcEventStorageEngine
import org.axonframework.serialization.Serializer
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@Configuration
@EnableConfigurationProperties(SnapshotProperties::class)
class AxonConfig {

    // DataSourceAutoConfiguration is skipped in WebFlux+R2DBC apps, so we provide
    // a dedicated JDBC DataSource for Axon's event store and transaction manager.
    @Bean
    fun jdbcDataSource(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        @Value("\${axon.jdbc.pool.size:50}") poolSize: Int,
    ): DataSource = HikariDataSource().apply {
        jdbcUrl = url
        this.username = username
        this.password = password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = poolSize
        connectionTimeout = 5_000
        poolName = "axon-jdbc-pool"
    }

    @Bean
    fun jdbcPlatformTransactionManager(jdbcDataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(jdbcDataSource)

    @Bean
    fun axonTransactionManager(jdbcPlatformTransactionManager: PlatformTransactionManager): TransactionManager =
        SpringTransactionManager(jdbcPlatformTransactionManager)

    @Bean
    fun eventSchema(): EventSchema = EventSchema.builder()
        .eventTable("domain_event_entry")
        .snapshotTable("snapshot_event_entry")
        .globalIndexColumn("global_index")
        .timestampColumn("time_stamp")
        .eventIdentifierColumn("event_identifier")
        .aggregateIdentifierColumn("aggregate_identifier")
        .sequenceNumberColumn("sequence_number")
        .typeColumn("type")
        .payloadTypeColumn("payload_type")
        .payloadRevisionColumn("payload_revision")
        .payloadColumn("payload")
        .metaDataColumn("meta_data")
        .build()

    @Bean
    fun tokenStore(
        jdbcDataSource: DataSource,
        serializer: Serializer,
    ): JdbcTokenStore = JdbcTokenStore.builder()
        .connectionProvider(DataSourceConnectionProvider(jdbcDataSource))
        .serializer(serializer)
        .schema(
            TokenSchema.builder()
                .setTokenTable("token_entry")
                .setProcessorNameColumn("processor_name")
                .setSegmentColumn("segment")
                .setTokenColumn("token")
                .setTokenTypeColumn("token_type")
                .setTimestampColumn("timestamp")
                .setOwnerColumn("owner")
                .build()
        )
        .build()

    @Bean
    fun eventStorageEngine(
        jdbcDataSource: DataSource,
        axonTransactionManager: TransactionManager,
        eventSchema: EventSchema,
        @Qualifier("eventSerializer") eventSerializer: Serializer,
        meterRegistry: MeterRegistry,
    ): EventStorageEngine {
        val jdbc = JdbcEventStorageEngine.builder()
            .connectionProvider(DataSourceConnectionProvider(jdbcDataSource))
            .transactionManager(axonTransactionManager)
            .schema(eventSchema)
            .eventSerializer(eventSerializer)
            .snapshotSerializer(eventSerializer)
            .build()
        // ES-2: wrap with timing decorator for state_load_time{phase} metrics
        return TimedEventStorageEngine(jdbc, meterRegistry)
    }

    @Bean
    fun inventorySnapshotTrigger(
        snapshotter: Snapshotter,
        snapshotProperties: SnapshotProperties,
    ): SnapshotTriggerDefinition =
        if (snapshotProperties.enabled)
            EventCountSnapshotTriggerDefinition(snapshotter, snapshotProperties.eventCount)
        else
            NoSnapshotTriggerDefinition.INSTANCE

    @Autowired
    fun configureProcessors(configurer: EventProcessingConfigurer) {
        configurer
            .registerTrackingEventProcessor("inventory-projection")
            .registerTrackingEventProcessor("mock-kafka-publisher")
    }
}
