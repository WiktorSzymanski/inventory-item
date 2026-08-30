package pl.szymanski.wiktor.config

import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import pl.szymanski.wiktor.domain.OrderItems
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.domain.SagaLines
import tools.jackson.databind.ObjectMapper

/**
 * Maps Order.items to a single JSONB column shaped {"<itemId>": <qty>}, the same format the
 * ES branch's orders projection stores, keeping the order INSERT a single-row write. Extending
 * AbstractJdbcConfiguration backs off Boot's default data-jdbc configuration while keeping
 * dialect-derived store conversions intact.
 */
@Configuration
class JdbcConversionsConfig(private val objectMapper: ObjectMapper) : AbstractJdbcConfiguration() {
    override fun userConverters(): List<*> = listOf(
        OrderItemsToJsonbConverter(objectMapper),
        JsonbToOrderItemsConverter(objectMapper),
        SagaLinesToJsonbConverter(objectMapper),
        JsonbToSagaLinesConverter(objectMapper),
    )
}

/**
 * The saga's line list, as a JSONB ARRAY — deliberately a different shape from the pair above.
 *
 * `OrderItemsToJsonbConverter` writes `{"<itemId>": qty}` because that is what the ES branch's
 * orders projection stores and the two have to be comparable. That shape is lossy in exactly the
 * two ways [SagaLines] cannot tolerate: a JSON object has no defined order, and it cannot hold the
 * same item twice. [OrderSaga.currentIndex] is a position in this list, so either loss silently
 * renumbers the saga's steps.
 *
 * Both converter pairs are registered at once, and Spring Data JDBC picks between them on the
 * source/target TYPE, which is why [SagaLines] exists as a distinct type rather than as a second
 * use of [OrderItems].
 */
@WritingConverter
class SagaLinesToJsonbConverter(
    private val objectMapper: ObjectMapper,
) : Converter<SagaLines, PGobject> {
    override fun convert(source: SagaLines): PGobject = PGobject().apply {
        type = "jsonb"
        value = objectMapper.writeValueAsString(source.lines)
    }
}

@ReadingConverter
class JsonbToSagaLinesConverter(
    private val objectMapper: ObjectMapper,
) : Converter<PGobject, SagaLines> {
    override fun convert(source: PGobject): SagaLines {
        val raw = source.value ?: "[]"
        val lines = objectMapper.readValue(raw, Array<ReservedItem>::class.java)
        return SagaLines(lines.toList())
    }
}

@WritingConverter
class OrderItemsToJsonbConverter(
    private val objectMapper: ObjectMapper,
) : Converter<OrderItems, PGobject> {
    override fun convert(source: OrderItems): PGobject = PGobject().apply {
        type = "jsonb"
        value = objectMapper.writeValueAsString(source.lines.associate { it.itemId to it.quantity })
    }
}

@ReadingConverter
class JsonbToOrderItemsConverter(
    private val objectMapper: ObjectMapper,
) : Converter<PGobject, OrderItems> {
    override fun convert(source: PGobject): OrderItems {
        val map = objectMapper.readValue(source.value ?: "{}", Map::class.java)
        return OrderItems(map.entries.map { (itemId, qty) -> ReservedItem(itemId as String, (qty as Number).toInt()) })
    }
}
