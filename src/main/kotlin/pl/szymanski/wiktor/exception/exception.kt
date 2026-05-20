package pl.szymanski.wiktor.exception

class NotFoundException(message: String) : RuntimeException(message)
class InsufficientStockException(message: String) : RuntimeException(message)
class OptimisticLockExhaustedException(message: String) : RuntimeException(message)
class ItemAlreadyExistsException(message: String) : RuntimeException(message)
class ReservationForThatItemAlreadyExistsException(message: String) : RuntimeException(message)