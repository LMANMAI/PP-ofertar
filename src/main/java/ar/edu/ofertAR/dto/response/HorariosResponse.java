package ar.edu.ofertAR.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Horario de atención de una sucursal, de lunes a domingo, tal como lo declara SEPA.
 *
 * <p>Es el contrato que usa la app para mostrar "Abierto / Cerrado", así que los
 * tres estados de un día se distinguen y nunca se mezclan:
 * <ul>
 *   <li>{@code []}: ese día está cerrado.</li>
 *   <li>{@code null}: ese día no está informado, o lo que dice SEPA no se entiende
 *       con certeza. La app no debe suponer ni abierto ni cerrado.</li>
 *   <li>una o más franjas: abre en esos tramos (dos en los de turno partido).</li>
 * </ul>
 * Las siete claves salen siempre, aun en null: por eso el {@code ALWAYS}, que pisa
 * cualquier configuración global que omita los null.
 *
 * <p>Si ningún día está informado no se arma un {@code HorariosResponse} con siete
 * null: la sucursal va sin horarios ({@code null}), que es lo mismo pero más claro.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo"})
public record HorariosResponse(
        List<FranjaHorariaResponse> lunes,
        List<FranjaHorariaResponse> martes,
        List<FranjaHorariaResponse> miercoles,
        List<FranjaHorariaResponse> jueves,
        List<FranjaHorariaResponse> viernes,
        List<FranjaHorariaResponse> sabado,
        List<FranjaHorariaResponse> domingo
) {

    /** Abierto las 24 horas: la única franja que ocupa el día entero. */
    public static final List<FranjaHorariaResponse> TODO_EL_DIA =
            List.of(new FranjaHorariaResponse("00:00", "24:00"));

    /**
     * Arma la semana a partir de los siete días en orden (lunes primero).
     *
     * @return null si ningún día está informado
     */
    public static HorariosResponse deDias(List<List<FranjaHorariaResponse>> dias) {
        if (dias == null || dias.size() != 7 || dias.stream().allMatch(Objects::isNull)) {
            return null;
        }
        return new HorariosResponse(dias.get(0), dias.get(1), dias.get(2), dias.get(3),
                dias.get(4), dias.get(5), dias.get(6));
    }

    /** Los siete días en orden, lunes primero. */
    @JsonIgnore
    public List<List<FranjaHorariaResponse>> dias() {
        return Arrays.asList(lunes, martes, miercoles, jueves, viernes, sabado, domingo);
    }

    /** Declara abierto las 24 horas los siete días. */
    @JsonIgnore
    public boolean esTodoElDiaTodaLaSemana() {
        return dias().stream().allMatch(TODO_EL_DIA::equals);
    }
}
