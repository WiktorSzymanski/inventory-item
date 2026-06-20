package pl.szymanski.wiktor.controller

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException
import java.util.concurrent.ConcurrentHashMap

data class ErrorResponse(val message: String)

@RestControllerAdvice
class GlobalExceptionHandler(private val meterRegistry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val exceptionCounters = ConcurrentHashMap<String, Counter>()

    init {
        listOf(
            "NotFoundException",
            "ItemAlreadyExistsException",
            "InsufficientStockException",
        ).forEach { exceptionCounter(it) }
    }

    private fun exceptionCounter(type: String): Counter =
        exceptionCounters.computeIfAbsent(type) {
            Counter.builder("inventory.exception").tag("type", it).register(meterRegistry)
        }

    private fun respond(e: Exception, logPrefix: String, message: String): ErrorResponse {
        log.warn("{}: {}", logPrefix, e.message)
        exceptionCounter(e.javaClass.simpleName).increment()
        return ErrorResponse(message)
    }

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: NotFoundException): ErrorResponse =
        respond(e, "Not found", e.message ?: "Not found")

    @ExceptionHandler(ItemAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleItemAlreadyExists(e: ItemAlreadyExistsException): ErrorResponse =
        respond(e, "Item already exists", e.message ?: "Item already exists")

    @ExceptionHandler(InsufficientStockException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun handleInsufficientStock(e: InsufficientStockException): ErrorResponse =
        respond(e, "Insufficient stock", e.message ?: "Insufficient stock")
}
