package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.SepaPreciosPageResponse;
import ar.edu.ofertAR.dto.response.SepaProductoDetalleResponse;
import ar.edu.ofertAR.dto.response.SepaProductoResponse;
import ar.edu.ofertAR.dto.response.SepaSucursalesCercanasResponse;
import ar.edu.ofertAR.dto.response.SepaSyncEstadoResponse;
import ar.edu.ofertAR.service.SepaCatalogoService;
import ar.edu.ofertAR.service.SepaService;
import ar.edu.ofertAR.service.SepaSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/sepa")
@RequiredArgsConstructor
@Tag(name = "SEPA", description = "Precios minoristas SEPA (datos.produccion.gob.ar)")
public class SepaController {

    private final SepaService sepaService;
    private final SepaSnapshotService sepaSnapshotService;
    private final SepaCatalogoService catalogo;

    @GetMapping("/precios")
    @Operation(summary = "Consulta EN VIVO contra el dataset SEPA (lento: descarga y parsea el zip; solo ADMIN)",
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
                    + "Ordenado por cantidad de ofertas. Incluye imagenUrl si ya fue resuelta; si viene null, "
                    + "el EAN queda encolado para resolverse en background.")
    public ResponseEntity<Page<SepaProductoResponse>> getProductos(
            @Parameter(description = "Búsqueda por palabras de la descripción o marca")
            @RequestParam(required = false) String q,
            @Parameter(description = "Código EAN exacto")
            @RequestParam(required = false) String ean,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (ean != null && !ean.isBlank()) {
            return ResponseEntity.ok(catalogo.porEan(ean, page, size));
        }
        return ResponseEntity.ok(catalogo.buscar(q, page, size));
    }

    @GetMapping("/productos/{ean}")
    @Operation(summary = "Detalle de un producto por EAN, pensado para el escaneo de código de barras",
            description = "Devuelve el agregado de precios, el desglose por comercio (del más barato al "
                    + "más caro) y la imagen. Responde 200 siempre: si el EAN no está en el snapshot, "
                    + "viene encontrado=false y se intenta completar nombre e imagen desde los proveedores "
                    + "externos, con un tope de tiempo corto.")
    public ResponseEntity<SepaProductoDetalleResponse> getProductoPorEan(@PathVariable String ean) {
        return ResponseEntity.ok(catalogo.detallePorEan(ean));
    }

    @GetMapping("/productos/{ean}/sucursales")
    @Operation(summary = "Dónde está más barato un producto cerca de una ubicación",
            description = "De cada cadena, la sucursal más barata dentro del radio (por defecto 5 km, "
                    + "máximo 30), con su dirección y coordenadas para navegar hasta ella. Los precios "
                    + "son por sucursal, no el mínimo de la cadena en todo el país. Responde 200 con "
                    + "la lista vacía si no hay sucursales con precio en el radio.")
    public ResponseEntity<SepaSucursalesCercanasResponse> getSucursalesCercanas(
            @PathVariable String ean,
            @Parameter(description = "Latitud del punto de búsqueda") @RequestParam double lat,
            @Parameter(description = "Longitud del punto de búsqueda") @RequestParam double lng,
            @Parameter(description = "Radio en km (1 a 30)") @RequestParam(defaultValue = "5") double radiusKm
    ) {
        return ResponseEntity.ok(catalogo.sucursalesCercanas(ean, lat, lng, radiusKm));
    }

    @PostMapping("/sync")
    @Operation(summary = "Dispara la sincronización del snapshot en DB (requiere rol ADMIN)",
            description = "Responde 202 al instante y sigue en background. "
                    + "Seguir el progreso con GET /sepa/sync/estado.")
    public ResponseEntity<SepaSyncEstadoResponse> sync(
            @RequestParam(required = false) String dia,
            @org.springframework.security.core.annotation.AuthenticationPrincipal ar.edu.ofertAR.model.User user
    ) {
        log.info("SEPA sync manual lanzado por userId={} dia={}", user == null ? null : user.getId(), dia);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sepaSnapshotService.lanzarAsync(dia));
    }

    @GetMapping("/sync/estado")
    @Operation(summary = "Estado de la sincronización en curso o de la última ejecutada (solo ADMIN)")
    public ResponseEntity<SepaSyncEstadoResponse> syncEstado() {
        return ResponseEntity.ok(sepaSnapshotService.getEstado());
    }
}
