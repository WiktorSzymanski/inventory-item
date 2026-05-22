package pl.szymanski.wiktor.config

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions

@Configuration
class JdbcConvertersConfig(private val objectMapper: ObjectMapper) {

    @Bean
    fun jdbcCustomConversions(): JdbcCustomConversions =
        JdbcCustomConversions(
            listOf(
                PGobjectToReservationsMapConverter(objectMapper),
                ReservationsMapToPGobjectConverter(objectMapper),
            )
        )

    @ReadingConverter
    class PGobjectToReservationsMapConverter(private val objectMapper: ObjectMapper) : Converter<PGobject, Map<String, Int>> {
        override fun convert(source: PGobject): Map<String, Int> =
            objectMapper.readValue(source.value ?: "{}", object : TypeReference<Map<String, Int>>() {})
    }

    @WritingConverter
    class ReservationsMapToPGobjectConverter(private val objectMapper: ObjectMapper) : Converter<Map<String, Int>, PGobject> {
        override fun convert(source: Map<String, Int>): PGobject = PGobject().apply {
            type = "jsonb"
            value = objectMapper.writeValueAsString(source)
        }
    }
}
