package com.shiftgrab.shared.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * After-commit module listeners ({@code @ApplicationModuleListener}) run asynchronously, so e-mail and live updates
 * never slow down or fail the request that caused them (SG-41).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {
}
