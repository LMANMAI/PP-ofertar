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
| Lenguaje | Java 17 |
| Framework | Spring Boot 3.5.0 |
| Persistencia | Spring Data JPA + Hibernate |
| Base de datos | MySQL |
| Seguridad | Spring Security |
| Build tool | Maven (via Maven Wrapper) |
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
└── mvnw / mvnw.cmd                         # Maven Wrapper (no requiere instalación)
```

---

## Requisitos previos

- Java 17+
- MySQL 8+
- No se requiere Maven instalado (se usa el wrapper incluido `./mvnw`)

---

## Configuración

Crear la base de datos en MySQL:

```sql
CREATE DATABASE ofertar_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Editar `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/ofertar_db
spring.datasource.username=tu_usuario
spring.datasource.password=tu_password
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
```

---

## Inicio rápido

```bash
# Clonar el repo
git clone https://github.com/LMANMAI/PP-ofertar.git
cd PP-ofertar

# Compilar y correr (Linux/Mac)
./mvnw spring-boot:run

# Compilar y correr (Windows)
mvnw.cmd spring-boot:run

# Correr tests
./mvnw test
```

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
