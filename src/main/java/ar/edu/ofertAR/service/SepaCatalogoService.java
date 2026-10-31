package ar.edu.ofertAR.service;

import ar.edu.ofertAR.dto.response.ComercioPrecioResponse;
import ar.edu.ofertAR.dto.response.SepaProductoDetalleResponse;
import ar.edu.ofertAR.dto.response.SepaProductoResponse;
import ar.edu.ofertAR.dto.response.SepaSucursalesCercanasResponse;
import ar.edu.ofertAR.model.SepaProducto;
import ar.edu.ofertAR.repository.SepaPrecioComercioRepository;
import ar.edu.ofertAR.repository.SepaProductoRepository;
import ar.edu.ofertAR.service.imagen.ProductoExterno;
import ar.edu.ofertAR.service.imagen.ProductoImagenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lectura del snapshot SEPA que carga la sincronización semanal: búsqueda de
 * productos, detalle por EAN y sucursales cercanas. Antes vivía en el controller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SepaCatalogoService {

    public static final String CACHE_BUSQUEDA = "sepaBusqueda";

    /** Radio por defecto y tope: el mismo rango que Mis tiendas favoritas (1 a 20 km) con margen. */
    private static final double RADIO_DEFECTO_KM = 5.0;
    private static final double RADIO_MAXIMO_KM = 30.0;

    /** InnoDB no indexa palabras de menos de 3 letras (innodb_ft_min_token_size). */
    private static final int MIN_TOKEN_FULLTEXT = 3;
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final SepaComercioNombres comercioNombres;
    private final SepaProductoRepository sepaProductoRepository;
    private final SepaPrecioComercioRepository sepaPrecioComercioRepository;
    private final ProductoImagenService productoImagenService;
    private final SepaSucursalPreciosService sucursalPreciosService;

    // ── Búsqueda ─────────────────────────────────────────────────────

    /**
     * Página de productos por texto (descripción o marca), del que tiene más ofertas al que tiene menos.
     * Cacheada: los datos cambian una vez por semana y la sincronización vacía este caché al terminar.
     */
    @Cacheable(cacheNames = CACHE_BUSQUEDA, key = "#q + '|' + #page + '|' + #size")
    public Page<SepaProductoResponse> buscar(String q, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        Page<SepaProducto> productos = buscarPagina(q == null ? "" : q.trim(), pageable);
        return new PageImpl<>(conImagenes(productos.getContent()), pageable, productos.getTotalElements());
    }

    public Page<SepaProductoResponse> porEan(String ean, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        SepaProducto producto = sepaProductoRepository.findByEan(ean.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay producto con EAN " + ean + " en el snapshot"));
        return new PageImpl<>(conImagenes(List.of(producto)), pageable, 1);
    }

    private Page<SepaProducto> buscarPagina(String term, PageRequest pageable) {
        Sort masOfertas = Sort.by(Sort.Direction.DESC, "cantidadOfertas");
        if (term.isEmpty()) {
            return sepaProductoRepository.findAll(pageable.withSort(masOfertas));
        }
        // Palabras "puras": sin operadores del modo booleano de MySQL.
        List<String> tokens = new ArrayList<>();
        for (String t : NO_ALFANUMERICO.split(term)) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        boolean usaIndice = !tokens.isEmpty() && tokens.stream().allMatch(t -> t.length() >= MIN_TOKEN_FULLTEXT);
        if (usaIndice) {
            // Cada palabra debe aparecer (como prefijo): "+leche* +desl*". Usa el índice FULLTEXT.
            String booleano = String.join(" ", tokens.stream().map(t -> "+" + t + "*").toList());
            try {
                return sepaProductoRepository.buscarTexto(booleano, pageable);
            } catch (org.springframework.dao.DataAccessException e) {
                // Sin el índice FULLTEXT (p.ej. falló su reconstrucción tras el sync) se busca como antes.
                log.warn("Búsqueda FULLTEXT no disponible, se usa LIKE: {}", e.getMessage());
            }
        }
        // Búsquedas de 1-2 letras: el índice no las cubre, se mantiene el "contiene" de siempre.
        return sepaProductoRepository.findByDescripcionContainingIgnoreCaseOrMarcaContainingIgnoreCase(
                term, term, pageable.withSort(masOfertas));
    }

    // ── Detalle por EAN (escaneo) ────────────────────────────────────

    public SepaProductoDetalleResponse detallePorEan(String ean) {
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

        return encontrado
                .map(this::detalleDesdeSnapshot)
                .orElseGet(() -> detalleDesdeProveedores(normalizado));
    }

    /** Camino normal: el producto está en el snapshot de SEPA. */
    private SepaProductoDetalleResponse detalleDesdeSnapshot(SepaProducto p) {
        List<ComercioPrecioResponse> comercios = sepaPrecioComercioRepository
                .findByEanOrderByPrecioMinimoAsc(p.getEan())
                .stream()
                .map(c -> ComercioPrecioResponse.from(c)
                        .withBandera(comercioNombres.nombre(c.getComercioId(), c.getBandera())))
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
     * SEPA no lo tiene. Se consulta la cadena externa en el momento, con tope de
     * tiempo y de concurrencia (ver {@link ProductoImagenService#buscarExterno}):
     * si el tercero no contesta a tiempo, la app recibe "no encontrado" igual.
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

    // ── Sucursales cercanas ──────────────────────────────────────────

    public SepaSucursalesCercanasResponse sucursalesCercanas(String ean, double lat, double lng, double radiusKm) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ubicación inválida");
        }
        String normalizado = ProductoImagenService.normalizarEan(ean);
        if (normalizado == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El código escaneado no es un EAN válido: " + ean);
        }
        double radio = Double.isFinite(radiusKm) && radiusKm >= 1 ? Math.min(radiusKm, RADIO_MAXIMO_KM) : RADIO_DEFECTO_KM;

        // El EAN tal cual vino primero: SEPA lo publica con formatos mezclados.
        Set<String> eans = new LinkedHashSet<>(List.of(ean.trim(), normalizado));
        var sucursales = sucursalPreciosService.masBaratasCerca(eans, lat, lng, radio);

        var fecha = eans.stream()
                .map(sepaProductoRepository::findByEan)
                .flatMap(Optional::stream)
                .map(SepaProducto::getFechaDataset)
                .findFirst()
                .orElse(null);

        return new SepaSucursalesCercanasResponse(normalizado, radio, fecha, sucursales);
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
