package pl.szymanski.wiktor.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.ReactivePageableHandlerMethodArgumentResolver
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer
import org.springframework.web.reactive.BindingContext
import org.springframework.core.MethodParameter
import org.springframework.web.server.ServerWebExchange

@Configuration
class WebConfig : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(ReactivePageableHandlerMethodArgumentResolver())
    }
}
