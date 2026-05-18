package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.MeterRegistry

fun MeterRegistry.timeIt(name: String, block: () -> Unit) {
    val timer = timer(name)
    timer.record(block)
}