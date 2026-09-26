# OfertAR — Backend API

> API REST para el asistente de ahorro inteligente del hogar argentino

Backend de OfertAR, encargado del procesamiento de tickets (OCR + IA), comparación de precios, autenticación de usuarios y gestión del historial de consumo.

---

## Contexto institucional

| Campo | Detalle |
|---|---|
| Institución | Instituto Técnico de Formación Superior "Leopoldo Marechal" |
| Carrera | Tecnicatura en Desarrollo de Software |
| Espacio Curricular | Prácticas Profesionalizantes 3 (PP3) |
| Ciclo Lectivo | 2026 |
| Docente | Mauro Julián Ayala |

---

## Stack tecnológico

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.5.0 |
| Persistencia | Spring Data JPA + Hibernate |
| Base de datos | MySQL |
| Seguridad | Spring Security |
| Build tool | Gradle (via Gradle Wrapper) |
| Utilidades | Lombok, Spring Validation |
| Tests | JUnit 5 + Spring Security Test |

---

## Estructura del proyecto

```
PP-ofertar/
├── src/
│   ├── main/
│   │   ├── java/ar/edu/ofertAR/
│   │   │   ├── PpOfertarApplication.java   # Entry point
│   │   │   ├── controller/                 # REST controllers
│   │   │   ├── service/                    # Lógica de negocio
│   │   │   ├── repository/                 # Acceso a datos (JPA)
│   │   │   ├── model/                      # Entidades JPA
│   │   │   ├── dto/                        # Data Transfer Objects
│   │   │   ├── security/                   # Config Spring Security / JWT
│   │   │   └── config/                     # Beans y configuración general
│   │   └── resources/
│   │       └── application.properties      # Config de entorno
│   └── test/
├── pom.xml
└── gradlew / gradlew.bat                   # Gradle Wrapper (no requiere instalación)
```

---

## Requisitos previos

- Java 21
- MySQL 8+
- No se requiere Gradle instalado (se usa el wrapper incluido `./gradlew`)

---

## Configuración

Crear la base de datos en MySQL:

```sql
CREATE DATABASE ofertar_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

La configuración sale de variables de entorno (ver `.env.example`). Son obligatorias y no tienen valor por
defecto, la app no arranca si falta alguna: `DB_PASSWORD`, `JWT_SECRET` (32+ caracteres) y `OCR_PASSWORD`.
Además: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `CORS_ALLOWED_ORIGINS` (solo si hay cliente web).

El esquema de la base lo gestiona **Flyway** (`src/main/resources/db/migration`); Hibernate solo valida
(`ddl-auto=validate`). Todo cambio de entidad necesita una migración nueva `V<n>__descripcion.sql`.

En desarrollo local activar el perfil `dev` (`SPRING_PROFILES_ACTIVE=dev`): muestra el SQL, habilita Swagger
en `/docs` y escribe en el log el código de recuperación de contraseña. Sin perfil la app corre con la
configuración segura de producción.

Los endpoints `/sepa/precios` y `/sepa/sync` requieren un usuario con rol `ADMIN`
(`UPDATE users SET role = 'ADMIN' WHERE email = '...'`).

---

## Inicio rápido

```bash
# Clonar el repo
git clone https://github.com/LMANMAI/PP-ofertar.git
cd PP-ofertar

# Variables de entorno (Linux/Mac; en PowerShell usar $env:NOMBRE = "valor")
export DB_PASSWORD=... JWT_SECRET=... OCR_PASSWORD=... SPRING_PROFILES_ACTIVE=dev

# Compilar y correr (Windows: gradlew.bat)
./gradlew bootRun

# Correr tests (necesitan un MySQL accesible con esas variables)
./gradlew test
```

Health check: `GET /actuator/health`.

La API quedara disponible en `http://localhost:8080`

---

## Módulos principales

### Autenticación
- Registro e inicio de sesión (nativo y Google Auth)
- JWT para protección de endpoints

### Tickets
- Recepción de imagen/PDF del ticket de compra
- Integración con motor OCR + LLM para extracción de productos → JSON
- Historial de compras por usuario

### Productos
- Categorización automática de productos
- Escaneo por código de barras
- Carrito manual (el usuario arma su lista sin escanear)
- Comparación entre últimos tickets para detectar productos faltantes

### Precios
- Comparador de precios entre supermercados (carrito completo y productos individuales)
- Trackeo de precios a lo largo del tiempo
- Notificaciones push de descuentos y promociones

### Geolocalización
- Sugerencia de supermercados cercanos con mejores precios

---

## Endpoints planeados (MVP)

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/auth/register` | Registro de usuario |
| `POST` | `/auth/login` | Login y obtención de JWT |
| `POST` | `/tickets/scan` | Subir y procesar ticket |
| `GET` | `/tickets` | Historial de tickets del usuario |
| `GET` | `/products` | Productos recurrentes del usuario |
| `GET` | `/prices/compare` | Comparar precios por producto/carrito |
| `GET` | `/offers` | Ofertas personalizadas detectadas |

---

## Proyecto relacionado

- **Frontend (React Native + Expo):** [LMANMAI/PP-ofertar-fe](https://github.com/LMANMAI/PP-ofertar-fe)

---

## CI/CD

- **Backport automático:** al mergear un PR a `main`, se abre automáticamente un PR de backport hacia `develop`.

---

## Contrato de la API (OpenAPI)

`openapi.json` en la raíz es el contrato que consume el frontend para generar sus tipos. Está versionado y el test
`OpenApiExportTest` falla si quedó desactualizado. Tras cambiar un DTO o un endpoint:

```bash
./gradlew test --tests '*OpenApiExportTest' -Dopenapi.update=true
```

y commitear el `openapi.json` resultante. En los DTO de respuesta (`dto/response`) todo campo es obligatorio y no nulo
salvo que esté anotado con `@Nullable` (viene siempre, pero puede valer `null`); los valores fijos se declaran con
`@Schema(allowableValues = ...)`. Después, en el repo de la app: `npm run api:sync && npm run api:types`.
