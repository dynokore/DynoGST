package com.dynokore.platform.serviceruntime.http

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

/** Registers [BindingFailureAdvice] (and [RequestTooLargeAdvice] when bean validation is present) in every servlet service on service-runtime (INV-AC-19580). */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.web.servlet.DispatcherServlet"])
class BindingFailureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BindingFailureAdvice::class)
    fun bindingFailureAdvice(): BindingFailureAdvice = BindingFailureAdvice()

    /** Registers [RequestTooLargeAdvice] (INV-AC-27818) when bean validation is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["jakarta.validation.ConstraintViolation"])
    class RequestTooLargeConfiguration {
        @Bean
        @ConditionalOnMissingBean(RequestTooLargeAdvice::class)
        fun requestTooLargeAdvice(resourceLoader: ResourceLoader): RequestTooLargeAdvice =
            RequestTooLargeAdvice.from(checkNotNull(resourceLoader.classLoader))
    }
}
