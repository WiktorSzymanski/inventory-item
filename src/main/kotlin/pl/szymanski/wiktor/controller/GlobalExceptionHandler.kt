package pl.szymanski.wiktor.controller

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
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

    init {
        listOf(
            "NotFoundException",
            "ItemAlreadyExistsException",
            "InsufficientStockException",
            "ReservationForThatItemAlreadyExistsException",
            "OptimisticLockExhaustedException",
            "OptimisticLockingFailureException",
        ).forEach { exceptionCounter(it) }
    }

    private fun exceptionCounter(type: String): Counter =
        Counter.builder("inventory.exception").tag("type", type).register(meterRegistry)

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: NotFoundException): ErrorResponse {
        log.warn("Not found: {}", e.message)
        exceptionCounter("NotFoundException").increment()
        return ErrorResponse(e.message ?: "Not found")
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

    @ExceptionHandler(OptimisticLockExhaustedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleOptimisticLockExhausted(e: OptimisticLockExhaustedException): ErrorResponse {
        log.warn("Optimistic lock exhausted: {}", e.message)
        exceptionCounter("OptimisticLockExhaustedException").increment()
        return ErrorResponse(e.message ?: "Too many concurrent requests, please retry")
    }

    @ExceptionHandler(OptimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleOptimisticLockingFailure(e: OptimisticLockingFailureException): ErrorResponse {
        log.warn("Optimistic lock retries exhausted: {}", e.message)
        exceptionCounter("OptimisticLockingFailureException").increment()
        return ErrorResponse("Too many concurrent requests, please retry")
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handlePessimisticLockingFailure(e: org.springframework.dao.PessimisticLockingFailureException): ErrorResponse {
        log.warn("Deadlock retries exhausted: {}", e.message)
        exceptionCounter("PessimisticLockingFailureException").increment()
        return ErrorResponse("Too many concurrent requests, please retry")
    }
}
