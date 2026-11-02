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

Los endpoints `/sepa/precios`, `/sepa/sync` y `/admin/**` requieren un usuario con rol `ADMIN`
(`UPDATE users SET role = 'ADMIN' WHERE email = '...'`).

### Dataset SEPA (precios por sucursal)

El snapshot de SEPA (`SepaSnapshotService`) baja el zip del dataset y lo carga en `sepa_producto`,
`sepa_sucursal` y compañía. `datos.produccion.gob.ar` responde 403 a IPs de datacenter, así que la fuente
es configurable. Todas las variables son opcionales; se usa la primera fuente seteada:

| Prioridad | Variable | Fuente |
|---|---|---|
| 1 | `SEPA_RESOURCE_FILE` | Un zip fijo dentro del contenedor. Gana siempre: sirve para forzar un dataset puntual. |
| 2 | `SEPA_RESOURCE_DIR` | Carpeta vigilada: el zip más reciente por la fecha de su contenido. La llena [`tools/sepa-relay`](tools/sepa-relay/README.md) desde una PC en Argentina. |
| 3 | `SEPA_RESOURCE_URL` | Un zip espejo http/https. |
| 4 | — | CKAN en vivo (`SEPA_CKAN_PACKAGE_URL`). |

- `SEPA_RESOURCE_FECHA` (`YYYY-MM-DD`): sólo para `FILE` o `URL`, cuando no se puede leer de adentro del zip
  (su carpeta `YYYY-MM-DD/`) ni del nombre. Con `SEPA_RESOURCE_DIR` se ignora.
- `SEPA_SYNC_CRON`: cron del sync programado (por defecto `0 0 3 * * MON`, hora Argentina). Con
  `SEPA_RESOURCE_DIR` conviene uno frecuente (`0 20 * * * *`): en ese modo el sync programado se saltea si el
  zip más nuevo no es posterior a `sepa_producto.fecha_dataset`. Con las demás fuentes importa siempre, y
  `POST /sepa/sync` (manual) también.
- `SEPA_BATCH_SIZE` / `SEPA_BATCH_PAUSA_MS`: tamaño de lote y pausa entre lotes de la carga.

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
- Productos seguidos (`/products/followed`): productos que el usuario sigue sin haberlos comprado. Un job diario (`followed.alert-cron`, por defecto 5:20, hora Argentina) consulta el servicio de ofertas en sus cadenas favoritas y manda una notificación push cuando alguno entra en promo

### Precios
- Comparador de precios entre supermercados (carrito completo y productos individuales)
- Trackeo de precios a lo largo del tiempo
- Notificaciones push de descuentos y promociones

### Notificaciones push
Se mandan por Expo (`https://exp.host/--/api/v2/push/send`) con prioridad `high`, para que Android no las
demore con el teléfono en reposo. Jobs diarios (hora Argentina): ofertas nuevas en las cadenas favoritas
(`offers.alert-cron`, 5:00), productos seguidos en promo (`followed.alert-cron`, 5:20) y recordatorio a quien
no escanea hace 14 días (`reactivation.cron`, 4:30). Los tres respetan `offersPushEnabled` del usuario.

| Método | Ruta | Quién | Descripción |
|---|---|---|---|
| `POST` | `/push/register` | usuario | Registra el token de Expo del dispositivo |
| `POST` | `/push/test` | usuario | Se manda a sí mismo una notificación de prueba. Cuerpo opcional `{ "type": "test" \| "followedProducts" \| "offers" \| "reactivation" }`: lleva el mismo `data` que el aviso real, así al tocarla abre la misma pantalla. No pasa por la deduplicación de los jobs. Devuelve el resultado de Expo por dispositivo. Una cada 10 s por usuario (429 con `Retry-After`); sin dispositivos responde 200 con `devices` vacío |
| `GET` | `/push/status` | usuario | Dispositivos registrados (plataforma, últimos 6 caracteres del token, `lastSeenAt`) y preferencias de notificación |
| `POST` | `/admin/push/jobs/{followed\|offers\|reactivation}?userId=` | ADMIN | Corre ahora la lógica del job para ese usuario y devuelve si mandó algo y por qué no. `offers` no marca las ofertas como vistas (esa marca es por cadena y le sacaría el aviso al resto) |

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
