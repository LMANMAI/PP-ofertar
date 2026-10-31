package ar.edu.ofertAR.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hace que el contrato OpenAPI diga la verdad sobre la nulabilidad, que es lo que el
 * frontend usa para generar sus tipos. En los DTO de respuesta, un campo es obligatorio
 * y no nulo salvo que esté anotado con {@link Nullable} (entonces viene siempre, pero puede
 * valer null); así quien agrega un campo lo declara no nulo por defecto y tiene que decidir
 * explícitamente si puede ser null.
 */
@Configuration
public class OpenApiConfig {

    private static final String PAQUETE_RESPUESTAS = "ar.edu.ofertAR.dto.response";

    @Bean
    public OpenApiCustomizer nulabilidadDeRespuestas() {
        Map<String, Class<?>> clases = clasesDeRespuesta();
        return (OpenAPI openApi) -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().forEach((nombre, schema) -> {
                Class<?> clase = clases.get(nombre);
                if (clase == null || schema.getProperties() == null) {
                    return;
                }
                aplicar(clase, schema);
            });
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void aplicar(Class<?> clase, Schema schema) {
        Map<String, Schema> propiedades = schema.getProperties();
        // Un DTO con NON_NULL omite los campos en null: ahí lo nulable pasa a ser opcional.
        boolean omiteNulos = clase.isAnnotationPresent(JsonInclude.class)
                && clase.getAnnotation(JsonInclude.class).value() == JsonInclude.Include.NON_NULL;
        List<String> obligatorias = new ArrayList<>();
        for (String propiedad : new ArrayList<>(propiedades.keySet())) {
            Field campo = campo(clase, propiedad);
            boolean nulable = campo != null && campo.isAnnotationPresent(Nullable.class);
            if (nulable) {
                propiedades.put(propiedad, nulable(propiedades.get(propiedad)));
            }
            if (!(nulable && omiteNulos)) {
                obligatorias.add(propiedad);
            }
        }
        schema.setRequired(obligatorias.isEmpty() ? null : obligatorias);
    }

    /** OpenAPI 3.0 ignora los hermanos de un $ref, así que un objeto nulable se envuelve en allOf. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nulable(Schema original) {
        if (original.get$ref() != null) {
            Schema envoltorio = new Schema<>();
            envoltorio.setAllOf(List.of(original));
            envoltorio.setNullable(true);
            return envoltorio;
        }
        original.setNullable(true);
        return original;
    }

    private static Field campo(Class<?> clase, String nombre) {
        for (Class<?> c = clase; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(nombre);
            } catch (NoSuchFieldException ignorar) {
                // seguir con la superclase
            }
        }
        return null;
    }

    /** Todas las clases del paquete de respuestas, incluidas las anidadas, por nombre simple (como las nombra springdoc). */
    private static Map<String, Class<?>> clasesDeRespuesta() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definicion) {
                return true;
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        Map<String, Class<?>> resultado = new HashMap<>();
        for (BeanDefinition definicion : scanner.findCandidateComponents(PAQUETE_RESPUESTAS)) {
            try {
                Class<?> clase = Class.forName(definicion.getBeanClassName());
                resultado.put(clase.getSimpleName(), clase);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return resultado;
    }
}
