package com.shiftgrab.shared.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document with a Bearer (JWT) security scheme so Swagger UI can call protected endpoints. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI shiftGrabOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShiftGrab API")
                        .version("v1")
                        .description("Shift registration and claiming. Errors follow RFC 7807 (application/problem+json)."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from Keycloak realm 'shiftgrab'")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
