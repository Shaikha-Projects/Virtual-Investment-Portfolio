package com.ga.investmentportfolio.Config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig  {
    String securitySchemeName = "bearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {

        //create  SecurityScheme describing how authentication works
        SecurityScheme securityScheme = new SecurityScheme().type(SecurityScheme.Type.HTTP)
                .scheme("bearer").bearerFormat("JWT");

        //attach/register that scheme with our OpenAPI configuration
        //add component to registers that description under "bearerAuth"
        Components components = new Components().addSecuritySchemes(securitySchemeName, securityScheme);

        SecurityRequirement securityRequirement = new SecurityRequirement().addList(securitySchemeName);

        return new OpenAPI().components(components).addSecurityItem(securityRequirement);

    }
}
