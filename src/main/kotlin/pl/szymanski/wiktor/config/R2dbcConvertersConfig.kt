package pl.szymanski.wiktor.config

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.PostgresDialect

@Configuration
class R2dbcConvertersConfig(private val objectMapper: ObjectMapper) {

    @Bean
    fun r2dbcCustomConversions(): R2dbcCustomConversions =
        R2dbcCustomConversions.of(
            PostgresDialect.INSTANCE,
            listOf(
                JsonToReservationsMapConverter(objectMapper),
                ReservationsMapToJsonConverter(objectMapper),
            )
        )

    @ReadingConverter
    class JsonToReservationsMapConverter(private val objectMapper: ObjectMapper) : Converter<Json, Map<String, Int>> {
        override fun convert(source: Json): Map<String, Int> =
            objectMapper.readValue(source.asString(), object : TypeReference<Map<String, Int>>() {})
    }

    @WritingConverter
    class ReservationsMapToJsonConverter(private val objectMapper: ObjectMapper) : Converter<Map<String, Int>, Json> {
        override fun convert(source: Map<String, Int>): Json = Json.of(objectMapper.writeValueAsString(source))
    }

}