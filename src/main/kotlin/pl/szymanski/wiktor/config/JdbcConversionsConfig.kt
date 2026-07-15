package pl.szymanski.wiktor.config

import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import pl.szymanski.wiktor.domain.OrderItems
import pl.szymanski.wiktor.domain.ReservedItem
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
    )
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
