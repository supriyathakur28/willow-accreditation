package com.supriya.accreditation;

import io.micronaut.openapi.annotation.OpenAPIGroupInfo;
import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

// One published contract per audience: the back-office never shares a document with the client application
@OpenAPIGroupInfo(
        names = "admin",
        info = @OpenAPIDefinition(info = @Info(
                title = "Accreditation Admin API",
                version = "1.0",
                description = "For back-office tooling: submit, decide and audit investors' accreditations.")))
@OpenAPIGroupInfo(
        names = "client",
        info = @OpenAPIDefinition(info = @Info(
                title = "Accreditation Client API",
                version = "1.0",
                description = "For the client application: a user's current accreditation statuses.")))
@SecurityScheme(
        name = "bearer",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class Application {

    public static void main(final String[] args) {
        Micronaut.run(Application.class, args);
    }
}
