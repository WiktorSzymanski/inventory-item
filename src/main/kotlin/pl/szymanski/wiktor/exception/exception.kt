package pl.szymanski.wiktor.exception

class NotFoundException(message: String) : Exception(message)
class InsufficientStockException(message: String) : Exception(message)
class OptimisticLockExhaustedException(message: String) : Exception(message)
class ItemAlreadyExistsException(message: String) : Exception(message)
class ReservationForThatItemAlreadyExistsException(message: String) : Exception(message)