package ar.edu.ofertAR.dto.response;

import org.springframework.lang.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    private int status;
    @Nullable
    private String message;
    @Nullable
    private Map<String, String> errors;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
