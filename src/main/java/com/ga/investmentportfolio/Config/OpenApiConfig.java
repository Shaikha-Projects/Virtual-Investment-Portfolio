package com.ga.investmentportfolio.Config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.info.Info;
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

        //general information about the API
        Info apiInfo = new Info()
                .title("Virtual Investment Portfolio System API")
                .version("1.0")
                .description(
                        "REST API for a virtual investment portfolio system. " +
                                "Users can manage their profiles, browse assets, buy and sell " +
                                "virtual investments, monitor holdings and portfolio performance, " +
                                "view transaction history, and manage a watchlist. " +
                                "Administrative endpoints allow authorized administrators to manage assets."
                );

        return new OpenAPI().info(apiInfo).components(components).addSecurityItem(securityRequirement);


    }
}
