package com.syncro.pedido.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.syncro.pedido.repository.PedidoRepository;
import com.syncro.pedido.repository.UsuarioRepository;
import com.syncro.pedido.repository.EmpresaRepository;

import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.syncro.pedido.dto.request.CambiarEstadoRequest;
import com.syncro.pedido.dto.request.CrearPedidoRequest;
import com.syncro.pedido.dto.response.DireccionResponse;
import com.syncro.pedido.dto.response.EmpresaResumenResponse;
import com.syncro.pedido.dto.response.HistorialEstadoResponse;
import com.syncro.pedido.dto.response.ItemPedidoResponse;
import com.syncro.pedido.dto.response.PedidoResponse;
import com.syncro.pedido.dto.response.PedidoResumenResponse;
import com.syncro.pedido.dto.response.UsuarioResumenResponse;
import com.syncro.pedido.event.PedidoEventPublisher;
import com.syncro.pedido.exception.PedidoNotFoundException;
import com.syncro.pedido.exception.TransaccionEstadoInvalidaException;
import com.syncro.pedido.model.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Servicio principal de la lógica de negocio para pedidos.
 *
 * Responsabilidades: - Crear pedidos validando empresa, usuario y dirección -
 * Gestionar las transiciones de estado del pedido - Consultar pedidos
 * individuales o por historial de empresa - Mapear entidades a DTOs de
 * respuesta
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PedidoService {

    private PedidoRepository pedidoRepository;
    private EmpresaRepository empresaRepository;
    private UsuarioRepository usuarioRepository;

    private PedidoEventPublisher eventPublisher;

    private static final Map<EstadoPedido, Set<EstadoPedido>> TRANSICIONES_VALIDAS = Map.of(
            EstadoPedido.PENDIENTE, EnumSet.of(EstadoPedido.CONFIRMADO, EstadoPedido.CANCELADO),
            EstadoPedido.CONFIRMADO, EnumSet.of(EstadoPedido.EN_PREPARACION, EstadoPedido.CANCELADO),
            EstadoPedido.EN_PREPARACION, EnumSet.of(EstadoPedido.DESPACHADO, EstadoPedido.CANCELADO),
            EstadoPedido.DESPACHADO, EnumSet.of(EstadoPedido.EN_RUTA),
            EstadoPedido.EN_RUTA, EnumSet.of(EstadoPedido.ENTREGADO),
            EstadoPedido.ENTREGADO, Collections.emptySet(),
            EstadoPedido.CANCELADO, Collections.emptySet()
    );

    /**
     * Crea un nuevo pedido en estado PENDIENTE.
     *
     * Pasos que realiza: 1. Valida que la empresa exista en la BD 2. Valida que
     * el usuario exista y pertenezca a esa empresa 3. Construye la dirección de
     * entrega 4. Construye los ítems y calcula subtotales y total 5. Registra
     * el primer historial de estado 6. Guarda todo en la BD con una sola
     * transacción
     *
     * param request datos del pedido enviados por el frontend return
     * PedidoResponse con el pedido recién creado
     */
    @Transactional
    public PedidoResponse crearPedido(CrearPedidoRequest request) {
        log.info("Iniciando creación de pedido para empresa ID={}", request.getEmpresaId());

        //paso 1 validar que la empresa existe
        Empresa empresa = empresaRepository.findById(request.getEmpresaId()).orElseThrow(() -> new IllegalArgumentException(
                "No existe empresa con ID: " + request.getEmpresaId()
        ));

        //paso 2 validar que el usuario existe y pertenecea la emrpresa
        Usuario usuario = usuarioRepository.findById(request.getUsuarioId()).orElseThrow(() -> new IllegalArgumentException(
                "No existe usuario con ID: " + request.getUsuarioId()
        ));

        if (!usuario.getEmpresa().getId().equals(empresa.getId())) {
            throw new IllegalArgumentException(
                    "El usuario ID=" + request.getUsuarioId()
                    + " no pertenecee a la empresa ID=" + request.getEmpresaId());
        }

        //paso 3 construir la direccion de entrega desde el request
        DireccionEntrega direccion = DireccionEntrega.builder()
                .calle(request.getDireccion().getCalle())
                .numero(request.getDireccion().getNumero())
                .depto(request.getDireccion().getDepto())
                .ciudad(request.getDireccion().getCiudad())
                .region(request.getDireccion().getRegion())
                // si no envian pais, se asigna Chile por defecto
                .pais(request.getDireccion().getPais() != null ? request.getDireccion().getPais() : "Chile")
                .codigoPostal(request.getDireccion().getCodigoPostal())
                .referencia(request.getDireccion().getReferencia())
                .build();

        //paso 4 construir el pedido base
        Pedido pedido = Pedido.builder()
                .empresa(empresa)
                .usuario(usuario)
                .direccion(direccion)
                .estado(EstadoPedido.PENDIENTE)
                .notas(request.getNotas())
                .referenciaExterna(request.getReferenciaExterna())
                .subtotal(BigDecimal.ZERO)
                .costoEnvio(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .items(new ArrayList<>())
                .historialEstados(new ArrayList<>())
                .fechaCreacion(LocalDateTime.now())
                .fechaActualizacion(LocalDateTime.now())
                .build();

        //paso 5 mapear los items y calcular el subtotal de cada uno
        List<ItemPedido> items = request.getItems().stream().map(
                itemReq -> ItemPedido.builder()
                        .pedido(pedido)
                        .sku(itemReq.getSku())
                        .nombre(itemReq.getNombre())
                        .cantidad(itemReq.getCantidad())
                        .precioUnitario(itemReq.getPrecioUnitario())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));

        pedido.setItems(items);

        // calcular subtotal sumando (cantidad * precio unitario) de cada item 
        BigDecimal subtotal = items.stream()
                .map(i -> i.getPrecioUnitario().multiply(BigDecimal.valueOf(i.getCantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        pedido.setSubtotal(subtotal);
        // el total es igual al subtotal (sin costo de envio por ahora)
        pedido.setTotal(subtotal);

        //paso 6 regisrar el estado inicial en el historial 
        HistorialEstado historialInicial = HistorialEstado.builder()
                .pedido(pedido)
                .estadoAnterior(null)
                .estadoNuevo(EstadoPedido.PENDIENTE)
                .actorId(usuario.getId())
                .actorTipo("USUARIO")
                .motivo("Creación del pedido")
                .fechaCambio(LocalDateTime.now())
                .build();

        pedido.getHistorialEstados().add(historialInicial);

        //paso 7 guardar todo en la BD (pedido + item + historial en una sola transaccion)
        Pedido guardado = pedidoRepository.save(pedido);
        log.info("Pedido ID={} creado exitosamente para empresa={}", guardado.getId(), empresa.getNombre());

        return mapToResponse(guardado);
    }

    /**
     * Cambia el estado de un pedido existente.
     *
     * Valida que la transición sea válida según las reglas de negocio. Registra
     * el cambio en el historial con fecha, actor y motivo.
     * param pedidoId ID del pedido a actualizar param request nuevo estado y
     * motivo opcional return PedidoResponse con el pedido actualizado
     */
    @Transactional
    public PedidoResponse cambiarEstado(Long pedidoId, CambiarEstadoRequest request) {
        log.info("Cambiando estado del pedido ID={} A {}", pedidoId, request.getNuevoEstado());

        //buscar el pedido o lanzar 404
        Pedido pedido = pedidoRepository.findById(pedidoId)
                .orElseThrow(() -> new PedidoNotFoundException(pedidoId));

        EstadoPedido estadoActual = pedido.getEstado();
        EstadoPedido nuevoEstado = request.getNuevoEstado();

        //validar que la transaccion este permitida
        Set<EstadoPedido> transaccionesPermitidas
                = TRANSICIONES_VALIDAS.getOrDefault(estadoActual, EnumSet.noneOf(EstadoPedido.class));

        if (!transaccionesPermitidas.contains(nuevoEstado)) {
            throw new TransaccionEstadoInvalidaException(estadoActual.name(),
                    nuevoEstado.name());
        }

        //obtener el id del usuario autenticado para el historial 
        Long actorId = getActorId();

        // Registrar el cambio en el historial
        HistorialEstado historial = HistorialEstado.builder()
                .pedido(pedido)
                .estadoAnterior(estadoActual)
                .estadoNuevo(nuevoEstado)
                .actorId(actorId)
                .actorTipo(actorId != null ? "USUARIO" : "SISTEMA")
                .motivo(request.getMotivo())
                .fechaCambio(LocalDateTime.now())
                .build();

        pedido.setEstado(nuevoEstado);

        if (nuevoEstado == EstadoPedido.CONFIRMADO) {
            pedido.setFechaConfirmacion(LocalDateTime.now());
        } else if (nuevoEstado == EstadoPedido.ENTREGADO) {
            pedido.setFechaEntrega(LocalDateTime.now());
        }
        pedido.getHistorialEstados().add(historial);

        // REEMPLAZAR por esto (un solo save, flag seteado antes):
        if (nuevoEstado == EstadoPedido.CONFIRMADO) {
        pedido.setEventoPublicado(true);
        }

        Pedido actualizado = pedidoRepository.save(pedido);
        log.info("Pedido ID={} cambió de {} a {}", pedidoId, estadoActual, nuevoEstado);

        if (nuevoEstado == EstadoPedido.CONFIRMADO) {
            eventPublisher.publicarPedidoCreado(actualizado);
            log.info("Evento pedido.creado publicado para pedido ID={}", actualizado.getId());
        }
        return mapToResponse(actualizado);
    }

    /**
     * Obtiene el detalle completo de un pedido por su ID. Incluye ítems,
     * dirección, empresa, usuario e historial de estados.
     *
     * param id ID del pedido return PedidoResponse con todos los datos del
     * pedido throws PedidoNotFoundException si no existe el pedido (HTTP 404)
     */
    @Transactional(readOnly = true)
    public PedidoResponse obtenerPedido(Long id) {
        log.debug("Consultando pedido ID={}", id);
        return pedidoRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new PedidoNotFoundException(id));
    }

    /**
     * Obtiene el historial de pedidos de una empresa con filtros opcionales.
     * Devuelve una lista resumida (sin ítems ni historial de estados) para no
     * sobrecargar la respuesta cuando hay muchos pedidos.
     *
     * Filtros disponibles (se aplica el primero que coincida): 1. Si se envía
     * "estado" → filtra por estado 2. Si se envía "desde" y "hasta" → filtra
     * por rango de fechas 3. Si no se envía ningún filtro → devuelve todos los
     * pedidos de la empresa
     *
     * param empresaId ID de la empresa param estado filtro opcional por estado
     * param desde filtro opcional fecha de inicio param hasta filtro opcional
     * fecha de fin return lista de PedidoResumenResponse
     */
    @Transactional(readOnly = true)
    public List<PedidoResumenResponse> obtenerHistorialPorEmpresa(
            Long empresaId,
            EstadoPedido estado,
            LocalDateTime desde,
            LocalDateTime hasta) {

        log.debug("Consultando historial de pedidos para empresa ID={}", empresaId);

        List<Pedido> pedidos;

        if (estado != null) {
            // Filtrar por estado específico
            pedidos = pedidoRepository.findByEmpresa_IdAndEstado(empresaId, estado);
        } else if (desde != null && hasta != null) {
            // Filtrar por rango de fechas
            pedidos = pedidoRepository.findByEmpresa_IdAndFechaCreacionBetween(empresaId, desde, hasta);
        } else {
            // Sin filtros: traer todos ordenados del más reciente al más antiguo
            pedidos = pedidoRepository.findByEmpresa_IdOrderByFechaCreacionDesc(empresaId);
        }

        return pedidos.stream().map(this::mapToResumen).toList();
    }

    // =========================================================================
    // MAPPERS — Convierten entidades en DTOs de respuesta
    // =========================================================================
    /**
     * Convierte un Pedido (entidad) en PedidoResponse (DTO completo). Se llama
     * al crear, actualizar o consultar un pedido individual.
     */
    private PedidoResponse mapToResponse(Pedido pedido) {

        // Mapear los ítems del pedido
        List<ItemPedidoResponse> itemsResponse = pedido.getItems().stream()
                .map(i -> ItemPedidoResponse.builder()
                .id(i.getId())
                .sku(i.getSku())
                .nombre(i.getNombre())
                .cantidad(i.getCantidad())
                .precioUnitario(i.getPrecioUnitario())
                .subtotal(i.getSubtotal())
                .build())
                .toList();

        // Mapear el historial de estados
        List<HistorialEstadoResponse> historialResponse = pedido.getHistorialEstados().stream()
                .map(h -> HistorialEstadoResponse.builder()
                .id(h.getId())
                .estadoAnterior(h.getEstadoAnterior())
                .estadoNuevo(h.getEstadoNuevo())
                .fechaCambio(h.getFechaCambio())
                .actorTipo(h.getActorTipo())
                .actorId(h.getActorId())
                .motivo(h.getMotivo())
                .build())
                .toList();

        // Mapear la empresa (solo datos resumidos)
        EmpresaResumenResponse empresaResponse = EmpresaResumenResponse.builder()
                .id(pedido.getEmpresa().getId())
                .nombre(pedido.getEmpresa().getNombre())
                .rut(pedido.getEmpresa().getRut())
                .build();

        // Mapear el usuario (nunca exponer la contraseña)
        UsuarioResumenResponse usuarioResponse = UsuarioResumenResponse.builder()
                .id(pedido.getUsuario().getId())
                .nombre(pedido.getUsuario().getNombre())
                .email(pedido.getUsuario().getEmail())
                .build();

        // Mapear la dirección de entrega
        DireccionResponse direccionResponse = DireccionResponse.builder()
                .id(pedido.getDireccion().getId())
                .calle(pedido.getDireccion().getCalle())
                .numero(pedido.getDireccion().getNumero())
                .depto(pedido.getDireccion().getDepto())
                .ciudad(pedido.getDireccion().getCiudad())
                .region(pedido.getDireccion().getRegion())
                .pais(pedido.getDireccion().getPais())
                .codigoPostal(pedido.getDireccion().getCodigoPostal())
                .referencia(pedido.getDireccion().getReferencia())
                .build();

        return PedidoResponse.builder()
                .id(pedido.getId())
                .empresa(empresaResponse)
                .usuario(usuarioResponse)
                .direccion(direccionResponse)
                .estado(pedido.getEstado())
                .subtotal(pedido.getSubtotal())
                .costoEnvio(pedido.getCostoEnvio())
                .total(pedido.getTotal())
                .notas(pedido.getNotas())
                .referenciaExterna(pedido.getReferenciaExterna())
                .fechaCreacion(pedido.getFechaCreacion())
                .fechaActualizacion(pedido.getFechaActualizacion())
                .fechaConfirmacion(pedido.getFechaConfirmacion())
                .fechaEntrega(pedido.getFechaEntrega())
                .items(itemsResponse)
                .historialEstados(historialResponse)
                .build();
    }

    /**
     * Convierte un Pedido (entidad) en PedidoResumenResponse (DTO resumido). Se
     * usa en listados donde no se necesita el detalle completo.
     */
    private PedidoResumenResponse mapToResumen(Pedido pedido) {
        return PedidoResumenResponse.builder()
                .id(pedido.getId())
                .empresaNombre(pedido.getEmpresa().getNombre())
                .usuarioNombre(pedido.getUsuario().getNombre())
                .estado(pedido.getEstado())
                .total(pedido.getTotal())
                .totalItems(pedido.getItems().size())
                .fechaCreacion(pedido.getFechaCreacion())
                .referenciaExterna(pedido.getReferenciaExterna())
                .build();
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================
    /**
     * Obtiene el ID del usuario autenticado desde el contexto de Spring
     * Security. Se usa para registrar quién realizó un cambio de estado en el
     * historial.
     *
     * Devuelve null si el cambio fue automático (ej: llamado interno del
     * sistema).
     */
    private Long getActorId() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return null;
            }
            String email = auth.getName();
            return usuarioRepository.findByEmail(email)
                    .map(Usuario::getId)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
