package com.skillbridge.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Publishes the API contract at {@code /v3/api-docs}.
 *
 * <p>Written after Phase 02 Task 1 closed twenty phantom endpoints — paths the
 * React app called that the backend had never implemented. Every one of them was
 * possible because the contract lived in two hand-maintained places at once:
 * {@code api/*.ts} on one side, {@code @RequestMapping} on the other, with
 * nothing comparing them. {@code scripts/check-api-drift.sh} compares them now;
 * this makes the backend side machine-readable so the frontend can eventually be
 * generated from it rather than typed out again.
 *
 * <p>Swagger UI is <b>off unless {@code SWAGGER_ENABLED} says otherwise</b> (see
 * {@code application.yaml}). It enumerates every endpoint and its shape, which
 * is a useful map for a developer and an equally useful one for anyone probing
 * the service. The JSON document itself stays available because the drift check
 * and client generation both read it.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI skillBridgeApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SkillBridge API")
                        .version("v1")
                        .description("""
                                Multi-tenant training management platform.

                                Every endpoint below is tenant-scoped: a caller sees only their own
                                college's data. Cross-tenant access returns **404, not 403** — a 403
                                would confirm to an attacker that the id they guessed exists and
                                belongs to someone else.

                                Errors share one shape (`ErrorResponse`): a stable `error` code to
                                branch on, a human `message`, and for 5xx a `correlationId` to quote
                                rather than an exception message."""))
                .servers(List.of(new Server().url("/").description("This server")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login")));
    }
}
