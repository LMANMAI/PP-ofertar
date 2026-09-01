package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.SepaPreciosPageResponse;
import ar.edu.ofertAR.dto.response.SepaProductoResponse;
import ar.edu.ofertAR.dto.response.SepaSyncEstadoResponse;
import ar.edu.ofertAR.model.SepaProducto;
import ar.edu.ofertAR.repository.SepaProductoRepository;
import ar.edu.ofertAR.service.SepaService;
import ar.edu.ofertAR.service.SepaSnapshotService;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sepa")
@RequiredArgsConstructor
@Tag(name = "SEPA", description = "Precios minoristas SEPA (datos.produccion.gob.ar)")
public class SepaController {

    private final SepaService sepaService;
    private final SepaSnapshotService sepaSnapshotService;
    private final SepaProductoRepository sepaProductoRepository;
    private final ProductoImagenService productoImagenService;

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
                    + "Precio mínimo/promedio/máximo entre todos los comercios y sucursales. "
                    + "Incluye imagenUrl si ya fue resuelta; si viene null, el EAN queda encolado "
                    + "para resolverse en background (la respuesta nunca espera al proveedor externo).")
    public ResponseEntity<Page<SepaProductoResponse>> getProductos(
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
            return ResponseEntity.ok(new PageImpl<>(conImagenes(List.of(producto)), pageable, 1));
        }

        String term = (q == null) ? "" : q.trim();
        Page<SepaProducto> productos = sepaProductoRepository
                .findByDescripcionContainingIgnoreCaseOrMarcaContainingIgnoreCase(term, term, pageable);

        return ResponseEntity.ok(new PageImpl<>(
                conImagenes(productos.getContent()), pageable, productos.getTotalElements()));
    }

    @PostMapping("/sync")
    @Operation(summary = "Dispara la sincronización del snapshot en DB (requiere JWT)",
            description = "Responde 202 al instante y sigue en background. "
                    + "Seguir el progreso con GET /sepa/sync/estado.")
    public ResponseEntity<SepaSyncEstadoResponse> sync(
            @RequestParam(required = false) String dia
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sepaSnapshotService.lanzarAsync(dia));
    }

    @GetMapping("/sync/estado")
    @Operation(summary = "Estado de la sincronización en curso o de la última ejecutada")
    public ResponseEntity<SepaSyncEstadoResponse> syncEstado() {
        return ResponseEntity.ok(sepaSnapshotService.getEstado());
    }

    /** Resuelve las imágenes de la página en UNA query, sin N+1. */
    private List<SepaProductoResponse> conImagenes(List<SepaProducto> productos) {
        Map<String, String> imagenes = productoImagenService.imagenesPorEan(
                productos.stream().map(SepaProducto::getEan).toList());

        return productos.stream()
                .map(p -> SepaProductoResponse.from(p,
                        imagenes.get(ProductoImagenService.normalizarEan(p.getEan()))))
                .toList();
    }
}
