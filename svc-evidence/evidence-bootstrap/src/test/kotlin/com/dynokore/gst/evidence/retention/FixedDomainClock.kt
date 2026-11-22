package com.dynokore.gst.evidence.retention

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * The domain [Clock] of every context [RetentionService.start] builds: the one clock the helper and the service resolve
 * 'today' from (INV-T-27351). Every context gets its own [AdvanceableClock]. Token issuance and validation keep the
 * system clock.
 */
@Configuration(proxyBeanMethods = false)
class FixedDomainClock {
    @Bean
    @Primary
    fun domainClock(): Clock = AdvanceableClock()
}

/** A test-controlled clock: it starts at [RetentionService.FIXED_CLOCK] and only [advance] moves it. */
class AdvanceableClock private constructor(
    private val now: AtomicReference<Instant>,
    private val zone: ZoneId,
) : Clock() {
    constructor() : this(AtomicReference(RetentionService.FIXED_CLOCK.instant()), ZoneOffset.UTC)

    override fun getZone(): ZoneId = zone

    override fun instant(): Instant = now.get()

    override fun withZone(zone: ZoneId): Clock = AdvanceableClock(now, zone)

    /** Moves this clock (and every [withZone] view of it) forward by [by]. */
    fun advance(by: Duration) {
        now.updateAndGet { it.plus(by) }
    }
}
