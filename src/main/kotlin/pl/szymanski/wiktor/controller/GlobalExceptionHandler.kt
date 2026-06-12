package pl.szymanski.wiktor.controller

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException

data class ErrorResponse(val message: String)

@RestControllerAdvice
class GlobalExceptionHandler(private val meterRegistry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(this::class.java)

    init {
        listOf(
            "NotFoundException",
            "ItemAlreadyExistsException",
            "InsufficientStockException",
            "TaskRejectedException",
        ).forEach { exceptionCounter(it) }
    }

    private fun exceptionCounter(type: String): Counter =
        Counter.builder("inventory.exception").tag("type", type).register(meterRegistry)

    private fun respond(e: Exception, logPrefix: String, fallbackMessage: String): ErrorResponse {
        log.warn("{}: {}", logPrefix, e.message)
        exceptionCounter(e.javaClass.simpleName).increment()
        return ErrorResponse(e.message ?: fallbackMessage)
    }

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: NotFoundException): ErrorResponse =
        respond(e, "Not found", "Not found")

    @ExceptionHandler(ItemAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleItemAlreadyExists(e: ItemAlreadyExistsException): ErrorResponse =
        respond(e, "Item already exists", "Item already exists")

    @ExceptionHandler(InsufficientStockException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun handleInsufficientStock(e: InsufficientStockException): ErrorResponse =
        respond(e, "Insufficient stock", "Insufficient stock")

    @ExceptionHandler(TaskRejectedException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleTaskRejected(e: TaskRejectedException): ErrorResponse {
        log.warn("Order worker queue full: {}", e.message)
        exceptionCounter("TaskRejectedException").increment()
        return ErrorResponse("Order processing queue is full, please retry later")
    }
}
