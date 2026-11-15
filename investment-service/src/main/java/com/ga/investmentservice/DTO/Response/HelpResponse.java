package com.ga.investmentservice.DTO.Response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record HelpResponse(String answer, List<Source> sources,
                           @JsonIgnore @Schema(hidden = true) boolean available) {
    public HelpResponse(String answer, List<Source> sources) {
        this(answer, sources, true);
    }

    public record Source(String sourceId, String title) {
    }
}
