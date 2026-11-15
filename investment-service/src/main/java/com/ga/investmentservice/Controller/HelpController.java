package com.ga.investmentservice.Controller;

import com.ga.investmentservice.DTO.Request.HelpRequest;
import com.ga.investmentservice.DTO.Response.ErrorResponse;
import com.ga.investmentservice.DTO.Response.HelpResponse;
import com.ga.investmentservice.Service.HelpService;
import com.ga.investmentservice.Service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/help")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Tag(name = "AI Help", description = "Documentation-based investor help")
@SecurityRequirement(name = "bearerAuth")
public class HelpController {
    private final ObjectProvider<HelpService> helpService;
    private final RateLimitService rateLimitService;

    @PostMapping(value = "/ask", consumes = "application/json", produces = "application/json")
    @Operation(summary = "Ask an application documentation question",
            description = "USER access only. Limited to 5 valid Help requests per minute per investor on this instance. Does not perform account actions.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer or safe documentation refusal",
                    content = @Content(schema = @Schema(implementation = HelpResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid question"),
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Investor USER role required"),
            @ApiResponse(responseCode = "429", description = "Help request limit reached"),
            @ApiResponse(responseCode = "503", description = "AI Help is disabled or temporarily unavailable")
    })
    public ResponseEntity<?> ask(@Valid @RequestBody HelpRequest request, Authentication authentication) {
        HelpService service = helpService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HelpResponse("AI Help is currently disabled.", List.of(), false));
        }
        if (!rateLimitService.allowHelpRequest(authentication.getName())) {
            HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
            return ResponseEntity.status(status).header("Retry-After", "60")
                    .body(new ErrorResponse(LocalDateTime.now(), status.value(), status.getReasonPhrase(),
                            "Too many Help requests. Please try again in one minute."));
        }
        HelpResponse response = service.answer(request.getQuestion());
        return ResponseEntity.status(response.available() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }
}
