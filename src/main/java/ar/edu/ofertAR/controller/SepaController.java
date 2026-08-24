package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.SepaPreciosPageResponse;
import ar.edu.ofertAR.dto.response.SepaSyncResponse;
import ar.edu.ofertAR.model.SepaProducto;
import ar.edu.ofertAR.repository.SepaProductoRepository;
import ar.edu.ofertAR.service.SepaService;
import ar.edu.ofertAR.service.SepaSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/sepa")
@RequiredArgsConstructor
@Tag(name = "SEPA", description = "Precios minoristas SEPA (datos.produccion.gob.ar)")
public class SepaController {

    private final SepaService sepaService;
    private final SepaSnapshotService sepaSnapshotService;
    private final SepaProductoRepository sepaProductoRepository;

    @GetMapping("/precios")
    @Operation(summary = "Consulta EN VIVO contra el dataset SEPA (lento: descarga y parsea el zip)",
            description = "Resuelve el recurso más reciente vía API CKAN (o el día indicado), lo cachea en disco "
                    + "y devuelve los precios normalizados, filtrados y paginados. "
                    + "La primera llamada del día puede tardar varios minutos.")
    public ResponseEntity<SepaPreciosPageResponse> getPrecios(
            @Parameter(description = "Día del recurso (lunes..viernes). Si se omite, usa el más reciente")
            @RequestParam(required = false) String dia,
            @Parameter(description = "Filtro por comercio: razón social, bandera, CUIT o id_comercio")
            @RequestParam(required = false) String comercio,
            @Parameter(description = "Filtro por descripción o marca del producto (contiene, sin acentos)")
            @RequestParam(required = false) String producto,
            @Parameter(description = "Filtro por código EAN exacto")
            @RequestParam(required = false) String ean,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ResponseEntity.ok(sepaService.getPrecios(dia, comercio, producto, ean, page, size));
    }

    @GetMapping("/productos")
    @Operation(summary = "Consulta el snapshot en DB (rápido; agregado por EAN)",
            description = "Busca en la tabla sepa_producto cargada por la sincronización semanal. "
                    + "Precio mínimo/promedio/máximo entre todos los comercios y sucursales.")
    public ResponseEntity<Page<SepaProducto>> getProductos(
            @Parameter(description = "Búsqueda por descripción o marca (contiene)")
            @RequestParam(required = false) String q,
            @Parameter(description = "Código EAN exacto")
            @RequestParam(required = false) String ean,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        if (ean != null && !ean.isBlank()) {
            SepaProducto producto = sepaProductoRepository.findByEan(ean.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No hay producto con EAN " + ean + " en el snapshot"));
            return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(
                    java.util.List.of(producto), pageable, 1));
        }
        String term = (q == null) ? "" : q.trim();
        return ResponseEntity.ok(sepaProductoRepository
                .findByDescripcionContainingIgnoreCaseOrMarcaContainingIgnoreCase(term, term, pageable));
    }

    @PostMapping("/sync")
    @Operation(summary = "Fuerza la sincronización del snapshot en DB (requiere JWT)",
            description = "Descarga el dataset más reciente (o el día indicado), lo agrega por EAN y "
                    + "reemplaza la tabla sepa_producto. Tarda varios minutos; responde al terminar.")
    public ResponseEntity<SepaSyncResponse> sync(
            @RequestParam(required = false) String dia
    ) {
        return ResponseEntity.ok(sepaSnapshotService.sync(dia));
    }
}
