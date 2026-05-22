package pl.szymanski.wiktor.controller

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.modelling.command.AggregateNotFoundException
import org.axonframework.modelling.command.ConcurrencyException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.exception.OptimisticLockExhaustedException
import pl.szymanski.wiktor.exception.ReservationForThatItemAlreadyExistsException

data class ErrorResponse(val message: String)

@RestControllerAdvice
class GlobalExceptionHandler(private val meterRegistry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private fun exceptionCounter(type: String): Counter =
        Counter.builder("inventory.exception").tag("type", type).register(meterRegistry)

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: NotFoundException): ErrorResponse {
        log.warn("Not found: {}", e.message)
        exceptionCounter("NotFoundException").increment()
        return ErrorResponse(e.message ?: "Not found")
    }

    @ExceptionHandler(AggregateNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleAggregateNotFound(e: AggregateNotFoundException): ErrorResponse {
        log.warn("Aggregate not found: {}", e.message)
        exceptionCounter("AggregateNotFoundException").increment()
        return ErrorResponse(e.message ?: "Item not found")
    }

    @ExceptionHandler(ItemAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleItemAlreadyExists(e: ItemAlreadyExistsException): ErrorResponse {
        log.warn("Item already exists: {}", e.message)
        exceptionCounter("ItemAlreadyExistsException").increment()
        return ErrorResponse(e.message ?: "Item already exists")
    }

    @ExceptionHandler(InsufficientStockException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun handleInsufficientStock(e: InsufficientStockException): ErrorResponse {
        log.warn("Insufficient stock: {}", e.message)
        exceptionCounter("InsufficientStockException").increment()
        return ErrorResponse(e.message ?: "Insufficient stock")
    }

    @ExceptionHandler(ReservationForThatItemAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleDuplicateReservation(e: ReservationForThatItemAlreadyExistsException): ErrorResponse {
        log.info("Duplicate reservation (idempotent): {}", e.message)
        exceptionCounter("ReservationForThatItemAlreadyExistsException").increment()
        return ErrorResponse(e.message ?: "Reservation already exists")
    }

    @ExceptionHandler(ConcurrencyException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleConcurrencyExhausted(e: ConcurrencyException): ErrorResponse {
        log.warn("Optimistic lock retries exhausted: {}", e.message)
        exceptionCounter("ConcurrencyException").increment()
        return ErrorResponse("Too many concurrent requests, please retry")
    }

    @ExceptionHandler(OptimisticLockExhaustedException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleOptimisticLockExhausted(e: OptimisticLockExhaustedException): ErrorResponse {
        log.warn("Optimistic lock exhausted: {}", e.message)
        exceptionCounter("OptimisticLockExhaustedException").increment()
        return ErrorResponse(e.message ?: "Too many concurrent requests, please retry")
    }
}
