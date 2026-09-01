package ar.edu.ofertAR.controller;

import ar.edu.ofertAR.dto.response.ComercioPrecioResponse;
import ar.edu.ofertAR.dto.response.SepaPreciosPageResponse;
import ar.edu.ofertAR.dto.response.SepaProductoDetalleResponse;
import ar.edu.ofertAR.dto.response.SepaProductoResponse;
import ar.edu.ofertAR.dto.response.SepaSyncEstadoResponse;
import ar.edu.ofertAR.model.SepaProducto;
import ar.edu.ofertAR.repository.SepaPrecioComercioRepository;
import ar.edu.ofertAR.repository.SepaProductoRepository;
import ar.edu.ofertAR.service.SepaService;
import ar.edu.ofertAR.service.SepaSnapshotService;
import ar.edu.ofertAR.service.imagen.ProductoExterno;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/sepa")
@RequiredArgsConstructor
@Tag(name = "SEPA", description = "Precios minoristas SEPA (datos.produccion.gob.ar)")
public class SepaController {

    private final SepaService sepaService;
    private final SepaSnapshotService sepaSnapshotService;
    private final SepaProductoRepository sepaProductoRepository;
    private final SepaPrecioComercioRepository sepaPrecioComercioRepository;
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

    @GetMapping("/productos/{ean}")
    @Operation(summary = "Detalle de un producto por EAN, pensado para el escaneo de código de barras",
            description = "Devuelve el agregado de precios, el desglose por comercio (del más barato al "
                    + "más caro) y la imagen. Responde 200 siempre: si el EAN no está en el snapshot, "
                    + "viene encontrado=false y se completa nombre e imagen desde los proveedores "
                    + "externos, para que la app pueda mostrar el producto escaneado igual.")
    public ResponseEntity<SepaProductoDetalleResponse> getProductoPorEan(@PathVariable String ean) {
        String normalizado = ProductoImagenService.normalizarEan(ean);
        if (normalizado == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El código escaneado no es un EAN válido: " + ean);
        }

        // SEPA publica el EAN con formatos mezclados (13 dígitos, 14 con cero
        // adelante), así que probamos tal cual vino y también normalizado.
        Optional<SepaProducto> encontrado = sepaProductoRepository.findByEan(ean.trim());
        if (encontrado.isEmpty() && !normalizado.equals(ean.trim())) {
            encontrado = sepaProductoRepository.findByEan(normalizado);
        }

        return ResponseEntity.ok(encontrado
                .map(this::detalleDesdeSnapshot)
                .orElseGet(() -> detalleDesdeProveedores(normalizado)));
    }

    /** Camino normal: el producto está en el snapshot de SEPA. */
    private SepaProductoDetalleResponse detalleDesdeSnapshot(SepaProducto p) {
        List<ComercioPrecioResponse> comercios = sepaPrecioComercioRepository
                .findByEanOrderByPrecioMinimoAsc(p.getEan())
                .stream()
                .map(ComercioPrecioResponse::from)
                .toList();

        Map<String, String> imagenes = productoImagenService.imagenesPorEan(List.of(p.getEan()));

        return SepaProductoDetalleResponse.builder()
                .ean(p.getEan())
                .encontrado(true)
                .sinPrecios(false)
                .fuenteDatos("sepa")
                .descripcion(p.getDescripcion())
                .marca(p.getMarca())
                .imagenUrl(imagenes.get(ProductoImagenService.normalizarEan(p.getEan())))
                .precioMinimo(p.getPrecioMinimo())
                .precioPromedio(p.getPrecioPromedio())
                .precioMaximo(p.getPrecioMaximo())
                .cantidadOfertas(p.getCantidadOfertas())
                .fechaDataset(p.getFechaDataset())
                .comercios(comercios)
                .build();
    }

    /**
     * SEPA no lo tiene. Se consulta la cadena externa en el momento —única
     * consulta sincrónica a un tercero en toda la API, justificada porque el
     * usuario acaba de escanear y está esperando.
     */
    private SepaProductoDetalleResponse detalleDesdeProveedores(String ean) {
        Optional<ProductoExterno> externo = productoImagenService.buscarExterno(ean);

        return SepaProductoDetalleResponse.builder()
                .ean(ean)
                .encontrado(false)
                .sinPrecios(true)
                .fuenteDatos(externo.isPresent() ? "externo" : "ninguna")
                .descripcion(externo.map(ProductoExterno::nombre).orElse(null))
                .marca(externo.map(ProductoExterno::marca).orElse(null))
                .imagenUrl(externo.map(ProductoExterno::imagenUrl).orElse(null))
                .comercios(List.of())
                .build();
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
