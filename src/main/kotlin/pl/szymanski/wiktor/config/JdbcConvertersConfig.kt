package pl.szymanski.wiktor.config

import org.postgresql.util.PGobject
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.data.jdbc.core.mapping.JdbcValue
import tools.jackson.databind.ObjectMapper
import java.sql.JDBCType

@Configuration
class JdbcConvertersConfig(private val objectMapper: ObjectMapper) {

    @Bean
    fun jdbcCustomConversions(): JdbcCustomConversions =
        JdbcCustomConversions(listOf(
            PGObjectToReservationsMapConverter(objectMapper),
            ReservationsMapToJdbcValueConverter(objectMapper),
        ))

    @ReadingConverter
    class PGObjectToReservationsMapConverter(private val objectMapper: ObjectMapper) : Converter<PGobject, Map<String, Int>> {
        override fun convert(source: PGobject): Map<String, Int> {
            val mapType = objectMapper.typeFactory.constructMapType(HashMap::class.java, String::class.java, Int::class.java)
            return objectMapper.readValue(source.value ?: "{}", mapType)
        }
    }

    // Returning JdbcValue carries the SQL type directly so Spring Data JDBC does not
    // infer Types.INTEGER from the Map<String, Int> value type parameter.
    @WritingConverter
    class ReservationsMapToJdbcValueConverter(private val objectMapper: ObjectMapper) : Converter<Map<String, Int>, JdbcValue> {
        override fun convert(source: Map<String, Int>): JdbcValue {
            val pgObject = PGobject().apply {
                type = "jsonb"
                value = objectMapper.writeValueAsString(source)
            }
            return JdbcValue.of(pgObject, JDBCType.OTHER)
        }
    }
}
