package pl.szymanski.wiktor.config

import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.KurrentDBConnectionString
import io.kurrent.dbclient.KurrentDBPersistentSubscriptionsClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

@Configuration
@EnableConfigurationProperties(KurrentDbProperties::class)
class KurrentDbConfig(private val props: KurrentDbProperties) {

    @Bean
    fun kurrentDBClient(): KurrentDBClient =
        KurrentDBClient.create(KurrentDBConnectionString.parseOrThrow(props.connectionString))

    @Bean
    fun kurrentDBPersistentSubscriptionsClient(): KurrentDBPersistentSubscriptionsClient =
        KurrentDBPersistentSubscriptionsClient.create(
            KurrentDBConnectionString.parseOrThrow(props.connectionString)
        )

    @Bean
    fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)
}
