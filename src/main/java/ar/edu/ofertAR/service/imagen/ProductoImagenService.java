package ar.edu.ofertAR.service.imagen;

import ar.edu.ofertAR.model.EstadoImagen;
import ar.edu.ofertAR.model.ProductoImagen;
import ar.edu.ofertAR.repository.ProductoImagenRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Resuelve y cachea la URL de la imagen de cada producto, por EAN.
 *
 * <p>El enriquecimiento NUNCA es sincrónico dentro de un request de usuario:
 * la API devuelve lo que ya está en DB y encola lo que falta. Así una consulta
 * a /sepa/productos nunca queda esperando a un tercero.
 *
 * <p>El trabajo sale por goteo ({@code imagenes.intervalo-ms} x
 * {@code imagenes.lote}) en vez de en una ráfaga, y prioriza lo que alguien
 * pidió de verdad por sobre el barrido del top N.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductoImagenService {

    private final ProductoImagenRepository repository;
    /** Todos los providers registrados, sin ordenar. */
    private final List<ImagenProvider> providers;

    /**
     * Orden de consulta, por nombre de proveedor.
     *
     * <p>VTEX va primero a propósito: los catálogos de supermercado tienen
     * packshot profesional sobre fondo blanco, que es lo que espera ver el
     * usuario de una app de precios. Open Food Facts es colaborativo y sus
     * fotos son las que sacan los contribuyentes con el celular: excelente
     * cobertura y licencia abierta, pero calidad despareja. Por eso queda de
     * respaldo, para la cola larga que los supermercados no tienen.
     *
     * <p>Es configurable para poder medir cobertura real de cada uno y
     * reordenar sin recompilar.
     */
    @Value("${imagenes.orden:vtex,openfoodfacts}")
    private List<String> orden;

    /** providers reordenados según {@code imagenes.orden}. */
    private List<ImagenProvider> cadena;

    @Value("${imagenes.enabled:true}")
    private boolean enabled;

    /** EANs procesados por tick del goteo. */
    @Value("${imagenes.lote:100}")
    private int lote;

    /** Reintento de NOT_FOUND / ERROR recién después de estos días. */
    @Value("${imagenes.reintentar-despues-de-dias:30}")
    private int reintentarDespuesDeDias;

    @Value("${imagenes.max-intentos:3}")
    private int maxIntentos;

    /** Tope de la cola bajo demanda; si se llena, se descartan los nuevos. */
    @Value("${imagenes.cola-maxima:5000}")
    private int colaMaxima;

    /** EANs que un usuario pidió y no teníamos. Se atienden antes que el top N. */
    private final LinkedBlockingQueue<String> colaPrioritaria = new LinkedBlockingQueue<>();
    /** Evita encolar mil veces el mismo EAN mientras espera turno. */
    private final Set<String> enCola = ConcurrentHashMap.newKeySet();

    /**
     * Arma la cadena: primero los nombrados en {@code imagenes.orden}, en ese
     * orden; después los que no figuren, respetando su @Order. Así agregar un
     * proveedor nuevo nunca lo deja fuera por olvidarse de listarlo.
     */
    @PostConstruct
    void ordenarCadena() {
        List<ImagenProvider> ordenados = new java.util.ArrayList<>();
        if (orden != null) {
            for (String nombre : orden) {
                String buscado = nombre.trim();
                providers.stream()
                        .filter(p -> p.nombre().equalsIgnoreCase(buscado))
                        .findFirst()
                        .ifPresent(ordenados::add);
            }
        }
        providers.stream().filter(p -> !ordenados.contains(p)).forEach(ordenados::add);
        this.cadena = List.copyOf(ordenados);
        log.info("Imágenes: cadena de proveedores = {}",
                cadena.stream().map(ImagenProvider::nombre).toList());
    }

    // ── Lectura (camino del request de usuario) ──────────────────────

    /**
     * URLs de imagen para un conjunto de EANs, en UNA sola query.
     * Los que no tienen imagen resuelta se encolan para el goteo.
     *
     * @return mapa EAN normalizado -> URL, solo con los que tienen imagen OK
     */
    public Map<String, String> imagenesPorEan(Collection<String> eans) {
        if (eans == null || eans.isEmpty()) {
            return Map.of();
        }
        // LinkedHashSet: deduplica y mantiene el orden de la página
        Set<String> normalizados = new LinkedHashSet<>();
        for (String ean : eans) {
            String n = normalizarEan(ean);
            if (n != null) {
                normalizados.add(n);
            }
        }
        if (normalizados.isEmpty()) {
            return Map.of();
        }

        Map<String, String> resultado = new HashMap<>();
        Set<String> resueltos = new HashSet<>();
        for (ProductoImagen imagen : repository.findAllById(normalizados)) {
            resueltos.add(imagen.getEan());
            if (imagen.getEstado() == EstadoImagen.OK && imagen.getUrl() != null) {
                resultado.put(imagen.getEan(), imagen.getUrl());
            }
        }

        if (enabled) {
            for (String ean : normalizados) {
                // Solo los que nunca vimos: si ya está NOT_FOUND, que lo maneje
                // el reintento por antigüedad, no cada request.
                if (!resueltos.contains(ean)) {
                    encolar(ean);
                }
            }
        }
        return resultado;
    }

    /** Encola un EAN para resolución en background (no bloquea). */
    public void encolar(String ean) {
        String normalizado = normalizarEan(ean);
        if (normalizado == null || colaPrioritaria.size() >= colaMaxima) {
            return;
        }
        if (enCola.add(normalizado)) {
            colaPrioritaria.offer(normalizado);
        }
    }

    // ── Goteo (camino de background) ─────────────────────────────────

    /**
     * Tick del enriquecimiento. Corre seguido pero chico: el objetivo es
     * repartir el tráfico saliente en el tiempo, no terminar rápido.
     */
    @Scheduled(fixedDelayString = "${imagenes.intervalo-ms:300000}",
               initialDelayString = "${imagenes.delay-inicial-ms:60000}")
    public void enriquecerLote() {
        if (!enabled) {
            return;
        }
        try {
            List<String> objetivo = proximoLote();
            if (objetivo.isEmpty()) {
                return;
            }
            int ok = 0;
            for (String ean : objetivo) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (resolver(ean).isPresent()) {
                    ok++;
                }
            }
            log.info("Imágenes: lote de {} EANs procesado, {} resueltos (cola pendiente: {})",
                    objetivo.size(), ok, colaPrioritaria.size());
        } catch (Exception e) {
            // Un tick que explota no puede matar el scheduler.
            log.error("Imágenes: el lote falló", e);
        }
    }

    /** Primero lo que pidió un usuario; el resto se completa con el top N. */
    private List<String> proximoLote() {
        List<String> objetivo = new java.util.ArrayList<>(lote);
        colaPrioritaria.drainTo(objetivo, lote);
        objetivo.forEach(enCola::remove);

        int faltan = lote - objetivo.size();
        if (faltan > 0) {
            LocalDateTime corte = LocalDateTime.now().minusDays(reintentarDespuesDeDias);
            objetivo.addAll(repository.findEansPendientes(corte, maxIntentos, faltan));
        }
        return objetivo;
    }

    /**
     * Consulta la cadena de proveedores para un EAN y persiste el resultado,
     * incluido el negativo. El cache negativo es lo que evita volver a
     * preguntar por los miles de EANs que nadie tiene fotografiados.
     */
    public Optional<String> resolver(String ean) {
        String normalizado = normalizarEan(ean);
        if (normalizado == null) {
            return Optional.empty();
        }

        ProductoImagen actual = repository.findById(normalizado).orElse(null);
        int intentosPrevios = actual == null ? 0 : actual.getIntentos();

        boolean huboError = false;
        for (ImagenProvider provider : cadena) {
            try {
                Optional<String> url = provider.buscarImagen(normalizado);
                if (url.isPresent()) {
                    guardar(normalizado, url.get(), provider.nombre(), EstadoImagen.OK, intentosPrevios);
                    return url;
                }
            } catch (ImagenProviderException e) {
                huboError = true;
                log.debug("Proveedor {} falló para {}: {}", provider.nombre(), normalizado, e.getMessage());
            }
        }

        // Distinguimos "ninguno lo tiene" de "no pudimos preguntar bien":
        // el segundo caso vuelve a la rueda de reintentos antes.
        EstadoImagen estado = huboError ? EstadoImagen.ERROR : EstadoImagen.NOT_FOUND;
        guardar(normalizado, null, null, estado, intentosPrevios + 1);
        return Optional.empty();
    }

    private void guardar(String ean, String url, String fuente, EstadoImagen estado, int intentos) {
        repository.save(ProductoImagen.builder()
                .ean(ean)
                .url(url)
                .fuente(fuente)
                .estado(estado)
                .intentos(intentos)
                .actualizadoAt(LocalDateTime.now())
                .build());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * SEPA publica EANs con formatos mezclados: 13 dígitos, 14 con cero
     * adelante, algunos con espacios. Los proveedores externos indexan por
     * EAN-13, así que normalizamos antes de consultar y de guardar.
     *
     * @return el EAN de 13 dígitos, o null si no es un EAN utilizable
     */
    public static String normalizarEan(String ean) {
        if (ean == null) {
            return null;
        }
        String digitos = ean.replaceAll("\\D", "");
        if (digitos.isEmpty()) {
            return null;
        }
        // GTIN-14 / EAN con ceros a la izquierda -> EAN-13
        digitos = digitos.replaceFirst("^0+(?=\\d{13}$)", "");
        if (digitos.length() > 14) {
            return null;
        }
        // EAN-8 y códigos internos más cortos se completan a 13
        if (digitos.length() < 13) {
            digitos = "0".repeat(13 - digitos.length()) + digitos;
        }
        // Un EAN de ceros o de un solo dígito repetido es basura del dataset
        if (digitos.chars().distinct().count() <= 1) {
            return null;
        }
        return digitos;
    }
}
