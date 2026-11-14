package ar.edu.ofertAR.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.lang.Nullable;

/** Cuerpo opcional de {@code POST /push/test}. Sin cuerpo o sin tipo, es "test". */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PushTestRequest {

    @Nullable
    @Schema(allowableValues = {"test", "followedProducts", "offers", "reactivation"},
            description = "Qué aviso simular. Lleva el mismo data que el aviso real, así al tocarlo abre la misma pantalla.")
    private String type;
}
