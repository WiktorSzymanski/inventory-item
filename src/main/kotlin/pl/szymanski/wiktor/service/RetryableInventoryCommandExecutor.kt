package pl.szymanski.wiktor.service

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReserveInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.ReserveItemCommand

@Service
class RetryableInventoryCommandExecutor(
    private val createInventoryItemCommandHandler: CreateInventoryItemCommandHandler,
    private val reserveInventoryItemCommandHandler: ReserveInventoryItemCommandHandler,
) {
    @Retryable(
        includes = [OptimisticLockingFailureException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun createItem(command: CreateItemCommand): InventoryItem =
        createInventoryItemCommandHandler.handle(command)

    @Retryable(
        includes = [OptimisticLockingFailureException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun reserveItem(command: ReserveItemCommand): String =
        reserveInventoryItemCommandHandler.handle(command)
}
