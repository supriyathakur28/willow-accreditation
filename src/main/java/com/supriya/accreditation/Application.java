package com.supriya.accreditation;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(
        info = @Info(
                title = "Accreditation Service",
                version = "1.0",
                description = "Tracks investors' accreditation status: admin commands and the client-facing read model"))
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
