package pl.szymanski.wiktor.controller

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
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: NotFoundException) = ErrorResponse(e.message ?: "Not found")

    @ExceptionHandler(ItemAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleItemAlreadyExists(e: ItemAlreadyExistsException) = ErrorResponse(e.message ?: "Item already exists")

    @ExceptionHandler(InsufficientStockException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun handleInsufficientStock(e: InsufficientStockException) = ErrorResponse(e.message ?: "Insufficient stock")

    @ExceptionHandler(ReservationForThatItemAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.OK)
    fun handleDuplicateReservation(e: ReservationForThatItemAlreadyExistsException) =
        ErrorResponse(e.message ?: "Reservation already exists")

    @ExceptionHandler(OptimisticLockExhaustedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleOptimisticLockExhausted(e: OptimisticLockExhaustedException) =
        ErrorResponse(e.message ?: "Too many concurrent requests, please retry")
}
