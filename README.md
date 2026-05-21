# MS-Pedidos — Documentación Técnica
**Proyecto:** Syncro — Plataforma de Gestión Logística eCommerce  
**Integrantes:** Sofía Gómez · Oriana Solorzano  
**Asignatura:** DSY1106 Desarrollo Fullstack III

---

## Índice

1. [Patrones de Diseño Implementados](#1-patrones-de-diseño-implementados)
2. [Arquetipos y Patrones Arquitectónicos](#2-arquetipos-y-patrones-arquitectónicos)
3. [Estrategia de Branching](#3-estrategia-de-branching)
4. [Buenas Prácticas y Pruebas Unitarias](#4-buenas-prácticas-y-pruebas-unitarias)
5. [Instrucciones de Instalación y Ejecución](#5-instrucciones-de-instalación-y-ejecución)

---

## 1. Patrones de Diseño Implementados

El microservicio MS-Pedidos aplica los siguientes patrones de diseño en el backend, cada uno elegido para resolver un problema concreto de mantenibilidad, desacoplamiento o seguridad.

### 1.1 Patrón Repository (Spring Data JPA)

**Problema que resuelve:** La lógica de negocio no debe conocer detalles de SQL ni de la capa de persistencia. Sin este patrón, `PedidoService` dependería directamente de queries SQL, acoplando la lógica de negocio al motor de base de datos.

**Implementación:** Cada entidad tiene su propia interfaz que extiende `JpaRepository<Entidad, Long>`. Spring Data genera automáticamente las implementaciones en tiempo de compilación. Las consultas personalizadas se definen con `@Query` JPQL o mediante métodos derivados del nombre.

```java
// PedidoRepository.java
List<Pedido> findByEmpresa_IdAndEstado(Long empresaId, EstadoPedido estado);

@Query("SELECT p FROM Pedido p WHERE p.estado = :estado AND p.eventoPublicado = false")
List<Pedido> findPedidosConfirmadosSinEvento(@Param("estado") EstadoPedido estado);
```

**Repositorios implementados:** `PedidoRepository`, `EmpresaRepository`, `UsuarioRepository`, `HistorialRepository`, `DireccionEntregaRepository`.

---

### 1.2 Patrón Publisher/Subscriber (RabbitMQ + Spring AMQP)

**Problema que resuelve:** Sin este patrón, MS-Pedidos debería llamar directamente a MS-Inventario y MS-Envíos de forma síncrona al confirmar un pedido. Si cualquiera de los dos servicios estuviera caído, el pedido entero fallaría. Además, agregar un nuevo consumidor requeriría modificar el código del publicador.

**Implementación:** Al cambiar el estado de un pedido a `CONFIRMADO`, `PedidoService` invoca a `PedidoEventPublisher`, que construye el evento y lo publica en el exchange `pedidos.exchange` (tipo Fanout). RabbitMQ distribuye el mensaje de forma independiente a dos colas:

| Cola | Consumidor | Acción |
|------|-----------|--------|
| `inventario.sincronizar` | MS-Inventario | Descuenta stock definitivamente |
| `envio.generar` | MS-Envíos | Crea registro de despacho con estado `PENDIENTE_RETIRO` |

```java
// PedidoService.java — cambiarEstado()
if (nuevoEstado == EstadoPedido.CONFIRMADO) {
    pedido.setEventoPublicado(true);
}
Pedido actualizado = pedidoRepository.save(pedido);

if (nuevoEstado == EstadoPedido.CONFIRMADO) {
    eventPublisher.publicarPedidoCreado(actualizado);
}
```

```java
// RabbitMQConfig.java
public static final String EXCHANGE = "pedidos.exchange";
public static final String COLA_INVENTARIO = "inventario.sincronizar";
public static final String COLA_ENVIOS = "envio.generar";

@Bean
public FanoutExchange exchange() {
    return new FanoutExchange(EXCHANGE);
}
```

**Beneficio:** MS-Pedidos no conoce a los consumidores. El acoplamiento es cero entre servicios. La flag `eventoPublicado` evita publicar el evento dos veces ante reintentos o fallos.

---

### 1.3 Patrón Chain of Responsibility — Filtro JWT (`JwtAuthFilter`)

**Problema que resuelve:** Cada petición HTTP debe ser autenticada antes de llegar al controller. Sin un filtro centralizado, cada endpoint necesitaría validar el token manualmente, duplicando código y generando superficie de ataque.

**Implementación:** `JwtAuthFilter` extiende `OncePerRequestFilter` y se ejecuta una sola vez por petición. Intercepta el header `Authorization: Bearer <token>`, extrae el email, carga el usuario desde BD y, si el token es válido, registra la autenticación en el contexto de Spring Security.

```java
// JwtAuthFilter.java
@Override
protected void doFilterInternal(HttpServletRequest request,
        HttpServletResponse response, FilterChain filterChain) {
    final String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        filterChain.doFilter(request, response);
        return;
    }
    final String token = authHeader.substring(7);
    final String email = jwtUtil.extractUsername(token);
    // ... carga usuario y registra autenticación
    SecurityContextHolder.getContext().setAuthentication(authToken);
    filterChain.doFilter(request, response);
}
```

**Rutas públicas excluidas del filtro:** `/auth/login`, `/auth/register`, `/swagger-ui/`, `/v3/api-docs`.

---

### 1.4 Patrón State — Máquina de Estados del Pedido

**Problema que resuelve:** Un pedido tiene un ciclo de vida con transiciones estrictamente controladas. Sin este patrón, cualquier endpoint podría mover un pedido a cualquier estado, generando inconsistencias operativas (ej. pasar de PENDIENTE directamente a ENTREGADO).

**Implementación:** `PedidoService` define un mapa inmutable de transiciones válidas. Antes de aplicar cualquier cambio de estado, se consulta este mapa y se lanza `TransaccionEstadoInvalidaException` (HTTP 409) si la transición no está permitida.

```java
// PedidoService.java
private static final Map<EstadoPedido, Set<EstadoPedido>> TRANSICIONES_VALIDAS = Map.of(
    EstadoPedido.PENDIENTE,      EnumSet.of(EstadoPedido.CONFIRMADO, EstadoPedido.CANCELADO),
    EstadoPedido.CONFIRMADO,     EnumSet.of(EstadoPedido.EN_PREPARACION, EstadoPedido.CANCELADO),
    EstadoPedido.EN_PREPARACION, EnumSet.of(EstadoPedido.DESPACHADO, EstadoPedido.CANCELADO),
    EstadoPedido.DESPACHADO,     EnumSet.of(EstadoPedido.EN_RUTA),
    EstadoPedido.EN_RUTA,        EnumSet.of(EstadoPedido.ENTREGADO),
    EstadoPedido.ENTREGADO,      Collections.emptySet(),
    EstadoPedido.CANCELADO,      Collections.emptySet()
);
```

**Flujo normal:** `PENDIENTE → CONFIRMADO → EN_PREPARACION → DESPACHADO → EN_RUTA → ENTREGADO`  
**Flujo de cancelación:** Disponible desde `PENDIENTE`, `CONFIRMADO` o `EN_PREPARACION`.

Cada transición queda registrada en `historial_estado_pedido` con timestamp, actor y motivo, satisfaciendo el requerimiento de trazabilidad RF-1.2.

---

### 1.5 Patrón DTO (Data Transfer Object)

**Problema que resuelve:** Exponer las entidades JPA directamente en la API revelaría datos sensibles (hash de contraseñas), generaría dependencias entre la API y el modelo de datos, y dificulta el versionamiento del contrato de la API.

**Implementación:** Cada operación tiene sus propios DTOs de entrada (Request) y salida (Response). El mapeo de entidad a DTO se realiza en `PedidoService` mediante builders. Las validaciones de entrada se declaran con anotaciones de Bean Validation (`@NotNull`, `@NotBlank`, `@Min`, `@Valid`).

```
dto/
├── request/
│   ├── CrearPedidoRequest.java    ← validaciones @NotNull, @Valid, @NotEmpty
│   ├── CambiarEstadoRequest.java  ← @NotNull en nuevoEstado
│   ├── DireccionRequest.java      ← @NotBlank en calle, ciudad, región
│   ├── ItemPedidoRequest.java     ← @Min(1) en cantidad, @DecimalMin en precio
│   ├── LoginRequest.java
│   └── RegisterRequest.java
└── response/
    ├── PedidoResponse.java        ← respuesta completa con ítems e historial
    ├── PedidoResumenResponse.java ← versión ligera para listados
    ├── AuthResponse.java          ← token + datos del usuario
    ├── EmpresaResumenResponse.java
    ├── UsuarioResumenResponse.java ← NUNCA expone la contraseña
    └── ...
```

---

## 2. Arquetipos y Patrones Arquitectónicos

### 2.1 Arquetipo Maven — Spring Boot Microservice

MS-Pedidos fue generado usando el arquetipo `spring-boot-starter-parent` (versión 4.0.6), que provee la estructura estándar de un microservicio Spring Boot con gestión centralizada de dependencias. El `pom.xml` define:

- **Gestión de dependencias:** Spring Data JPA, Spring Security, Spring AMQP, Validation, Web.
- **Plugin de compilación:** `maven-compiler-plugin` con soporte a Lombok mediante `annotationProcessorPaths`.
- **Plugin de empaquetado:** `spring-boot-maven-plugin` con exclusión de Lombok en el artefacto final.

```
src/
├── main/
│   ├── java/com/syncro/pedido/
│   │   ├── config/        ← SecurityConfig, RabbitMQConfig, SwaggerConfig, CorsConfig
│   │   ├── controller/    ← PedidoController, AuthController, EmpresaController
│   │   ├── dto/           ← request/ y response/
│   │   ├── event/         ← PedidoCreadoEvent, PedidoEventPublisher
│   │   ├── exception/     ← GlobalExceptionHandler, excepciones de dominio
│   │   ├── model/         ← Entidades JPA
│   │   ├── repository/    ← Interfaces JpaRepository
│   │   └── service/       ← PedidoService, AuthService, EmpresaService
│   └── resources/
│       └── application.properties
└── test/
    ├── java/com/syncro/pedido/
    │   ├── controller/    ← AuthControllerTest, PedidoControllerTest
    │   ├── security/      ← JwtUtilTest
    │   └── service/       ← AuthServiceTest, PedidoServiceTest
    └── resources/
        └── application.properties ← H2 + RabbitMQ desactivado
```

### 2.2 Patrón Arquitectónico: Microservicios con EDA (Event-Driven Architecture)

**Justificación de escalabilidad:** Cada microservicio (MS-Pedidos :8083, MS-Inventario :8082, MS-Envíos :8084) opera de forma completamente independiente con su propia base de datos MySQL en Aiven, implementando el patrón **Database per Service**. Un fallo en la BD de MS-Pedidos no interrumpe MS-Inventario ni MS-Envíos.

**Justificación de coherencia:** Las transacciones dentro de MS-Pedidos son atómicas: al crear un pedido, los ítems, la dirección y el primer historial de estado se persisten en una sola transacción `@Transactional`. No existen foreign keys cruzadas entre bases de datos; la referencia entre servicios se realiza mediante IDs lógicos (el SKU referencia a MS-Inventario, pero no es una FK cruzada).

**Justificación de rendimiento:** Las operaciones secundarias (descuento de stock, creación del despacho) se ejecutan de forma asíncrona vía RabbitMQ. El usuario recibe la confirmación del pedido sin esperar que MS-Inventario o MS-Envíos procesen sus colas.

### 2.3 Patrón Arquitectónico: API Gateway

Spring Cloud Gateway (puerto :8080) actúa como único punto de entrada al sistema, validando el token JWT antes de enrutar al microservicio correspondiente. MS-Pedidos no queda expuesto directamente a Internet; toda petición pasa primero por el gateway.

| Ruta | Microservicio | Puerto |
|------|--------------|--------|
| `/pedidos/**` | MS-Pedidos | 8083 |
| `/inventario/**` | MS-Inventario | 8082 |
| `/envios/**` | MS-Envíos | 8084 |

### 2.4 Patrón de Seguridad: JWT Stateless

MS-Pedidos implementa autenticación stateless con Spring Security + JWT. No se mantienen sesiones en el servidor. El token JWT incluye el email del usuario y sus roles, firmado con HMAC-SHA256. La clave secreta y el tiempo de expiración (24h) se configuran vía variables de entorno.

```
Variables de entorno requeridas:
DB_URL, DB_USER, DB_PASS     ← MySQL (Aiven)
JWT_SECRET                    ← mínimo 32 caracteres
RABBIT_HOST, RABBIT_USER,
RABBIT_PASS, RABBIT_VHOST    ← CloudAMQP
```

---

## 3. Estrategia de Branching

El proyecto utiliza **Git Flow** como estrategia de branching, con la siguiente estructura de ramas:

| Rama | Propósito | Reglas |
|------|-----------|--------|
| `main` | Código de producción | Solo recibe merges desde `release/*` o `hotfix/*`. Nunca se desarrolla directamente. |
| `develop` | Rama de integración | Base para todas las features. Se mantiene siempre actualizada y estable. |
| `feature/*` | Nueva funcionalidad | Se crea desde `develop`. Se mergea a `develop` por Pull Request con revisión. |
| `fix/*` | Corrección de bug | Se crea desde `develop`. Se mergea a `develop`. |
| `release/*` | Preparación de versión | Se crea desde `develop`. Se mergea a `main` y a `develop`. |
| `hotfix/*` | Corrección urgente en producción | Se crea desde `main`. Se mergea a `main` y a `develop`. |

### Convención de commits (Conventional Commits)

Todos los commits siguen el estándar Conventional Commits para mantener el historial legible y permitir la generación automática del CHANGELOG:

| Prefijo | Uso |
|---------|-----|
| `feat` | Nueva funcionalidad |
| `fix` | Corrección de bug |
| `refactor` | Refactorización sin cambio funcional |
| `docs` | Cambios en documentación |
| `test` | Agregar o modificar tests |
| `chore` | Tareas de mantenimiento y configuración |

### Versionamiento semántico

Se aplica versionamiento semántico `MAJOR.MINOR.PATCH`:
- `PATCH` — correcciones de bugs compatibles hacia atrás.
- `MINOR` — nueva funcionalidad compatible hacia atrás.
- `MAJOR` — cambios que rompen compatibilidad.

---

## 4. Buenas Prácticas y Pruebas Unitarias

### 4.1 Código limpio y organizado

- **Separación de responsabilidades:** Controllers solo manejan HTTP; la lógica de negocio reside en Services; la persistencia en Repositories.
- **Inmutabilidad:** Las entidades usan `@Builder` de Lombok. El mapa de transiciones de estados es `Map.of(...)` (inmutable).
- **Manejo centralizado de errores:** `GlobalExceptionHandler` con `@RestControllerAdvice` captura todas las excepciones del dominio y retorna respuestas JSON estructuradas con el código HTTP correspondiente.
- **Logging:** Todas las clases de servicio usan `@Slf4j` para trazabilidad en producción.
- **Variables de entorno:** Ninguna credencial está hardcodeada. Todas se inyectan con `${VAR}` desde `application.properties`.
- **Documentación automática:** Swagger/OpenAPI disponible en `/swagger-ui.html` con autenticación JWT integrada en la interfaz.

### 4.2 Pruebas unitarias

Las pruebas se encuentran en `src/test/java/com/syncro/pedido/` y utilizan **JUnit 5 + Mockito** con perfil H2 (base de datos en memoria) y RabbitMQ desactivado.

#### Cobertura por clase

| Clase testeada | Archivo de test | Tests implementados |
|----------------|-----------------|---------------------|
| `PedidoService` | `PedidoServiceTest.java` | 12 tests |
| `AuthService` | `AuthServiceTest.java` | 7 tests |
| `PedidoController` | `PedidoControllerTest.java` | 6 tests |
| `AuthController` | `AuthControllerTest.java` | 2 tests |
| `JwtUtil` | `JwtUtilTest.java` | 7 tests |
| **Total** | | **34 tests** |

#### Escenarios cubiertos en `PedidoServiceTest`

| Test | Escenario |
|------|-----------|
| `crearPedido_Success` | Pedido creado correctamente con empresa y usuario válidos |
| `crearPedido_EmpresaNotFound_ThrowsException` | Empresa inexistente → `IllegalArgumentException` |
| `crearPedido_UsuarioNotFound_ThrowsException` | Usuario inexistente → `IllegalArgumentException` |
| `crearPedido_UsuarioNotFromEmpresa_ThrowsException` | Usuario de otra empresa → `IllegalArgumentException` |
| `cambiarEstado_ValidTransition_Success` | PENDIENTE → CONFIRMADO, evento publicado, fecha seteada |
| `cambiarEstado_InvalidTransition_ThrowsException` | PENDIENTE → ENTREGADO → `TransaccionEstadoInvalidaException` |
| `cambiarEstado_PedidoNotFound_ThrowsException` | ID inexistente → `PedidoNotFoundException` |
| `cambiarEstado_CancelFromPending_Success` | PENDIENTE → CANCELADO, sin publicar evento |
| `cambiarEstado_Entregado_SetsFechaEntrega` | EN_RUTA → ENTREGADO, `fechaEntrega` seteada |
| `obtenerPedido_Success` | Pedido encontrado por ID |
| `obtenerPedido_NotFound_ThrowsException` | ID inexistente → `PedidoNotFoundException` |
| `obtenerHistorialPorEmpresa_*` | Filtros por estado, rango de fechas y sin filtros |

#### Escenarios cubiertos en `JwtUtilTest`

| Test | Escenario |
|------|-----------|
| `generateToken_Success` | Token generado con estructura `header.payload.signature` |
| `extractUsername_Success` | Email extraído correctamente del payload |
| `validateToken_ValidToken_ReturnsTrue` | Token válido para el usuario correcto |
| `validateToken_ExpiredToken_ThrowsException` | Token expirado lanza `ExpiredJwtException` |
| `validateToken_WrongUser_ReturnsFalse` | Token de otro usuario retorna `false` |
| `validateToken_ManipulatedToken_ThrowsException` | Token manipulado lanza `JwtException` |

### 4.3 Configuración de tests

El perfil de test (`src/test/resources/application.properties`) usa H2 en memoria y deshabilita la auto-configuración de RabbitMQ, permitiendo ejecutar todos los tests sin dependencias externas:

```properties
spring.datasource.url=jdbc:h2:mem:testdb
spring.jpa.hibernate.ddl-auto=create-drop
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration
jwt.secret=test-secret-key-for-jwt-token-generation-must-be-at-least-32-chars
```

---

## 5. Instrucciones de Instalación y Ejecución

### Prerrequisitos

- Java 21
- Maven 3.9+
- Variables de entorno configuradas (ver sección 2.4)

### Ejecutar con Maven

```bash
# Clonar el repositorio
git clone <url-repositorio>
cd ms-pedidos

# Compilar
./mvnw clean compile

# Ejecutar tests
./mvnw test

# Ejecutar la aplicación
./mvnw spring-boot:run
```

### Endpoints disponibles

| Método | Ruta | Descripción | Auth |
|--------|------|-------------|------|
| `POST` | `/empresas` | Crear empresa | Pública |
| `POST` | `/auth/register` | Registrar operador | Pública |
| `POST` | `/auth/login` | Iniciar sesión → JWT | Pública |
| `POST` | `/pedidos` | Crear pedido | JWT |
| `GET` | `/pedidos/{id}` | Detalle de pedido | JWT |
| `PATCH` | `/pedidos/{id}/estado` | Cambiar estado | JWT |
| `GET` | `/pedidos/historial/{empresaId}` | Historial con filtros | JWT |

### Documentación interactiva

Con la aplicación en ejecución, acceder a:

```
http://localhost:8083/swagger-ui.html
```

Autenticarse con el botón **Authorize** usando el token JWT obtenido en `/auth/login`.

---

*Documento generado para la Evaluación Parcial N°2 — DSY1106 Desarrollo Fullstack III*
