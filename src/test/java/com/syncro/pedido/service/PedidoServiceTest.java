package com.syncro.pedido.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.syncro.pedido.dto.request.CambiarEstadoRequest;
import com.syncro.pedido.dto.request.CrearPedidoRequest;
import com.syncro.pedido.dto.request.DireccionRequest;
import com.syncro.pedido.dto.request.ItemPedidoRequest;
import com.syncro.pedido.dto.response.PedidoResponse;
import com.syncro.pedido.dto.response.PedidoResumenResponse;
import com.syncro.pedido.event.PedidoEventPublisher;
import com.syncro.pedido.exception.PedidoNotFoundException;
import com.syncro.pedido.exception.TransaccionEstadoInvalidaException;
import com.syncro.pedido.model.DireccionEntrega;
import com.syncro.pedido.model.Empresa;
import com.syncro.pedido.model.EstadoPedido;
import com.syncro.pedido.model.Pedido;
import com.syncro.pedido.model.Rol;
import com.syncro.pedido.model.Usuario;
import com.syncro.pedido.repository.EmpresaRepository;
import com.syncro.pedido.repository.PedidoRepository;
import com.syncro.pedido.repository.UsuarioRepository;

@ExtendWith(MockitoExtension.class)
class PedidoServiceTest {

    @Mock
    private PedidoRepository pedidoRepository;

    @Mock
    private EmpresaRepository empresaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PedidoEventPublisher eventPublisher;

    @InjectMocks
    private PedidoService pedidoService;

    private Empresa empresa;
    private Usuario usuario;
    private CrearPedidoRequest crearPedidoRequest;
    private DireccionRequest direccionRequest;
    private ItemPedidoRequest itemRequest;
    private Pedido pedido;
    private DireccionEntrega direccionEntrega;

    @BeforeEach
    void setUp() {
        empresa = Empresa.builder()
                .id(1L)
                .nombre("Test Empresa")
                .rut("12345678-9")
                .email("contacto@test.com")
                .activo(true)
                .build();

        usuario = Usuario.builder()
                .id(1L)
                .nombre("Test User")
                .email("test@test.com")
                .password("encodedPassword")
                .empresa(empresa)
                .rol(Rol.OPERADOR)
                .activo(true)
                .build();

        direccionRequest = DireccionRequest.builder()
                .calle("Av. Providencia")
                .numero("1234")
                .depto("502")
                .ciudad("Santiago")
                .region("Región Metropolitana")
                .pais("Chile")
                .codigoPostal("7500000")
                .referencia("Casa con reja verde")
                .build();

        itemRequest = ItemPedidoRequest.builder()
                .sku("ELEC-001")
                .nombre("Audífonos BT")
                .cantidad(2)
                .precioUnitario(new BigDecimal("29990"))
                .build();

        direccionEntrega = DireccionEntrega.builder()
                .id(1L)
                .calle("Av. Providencia")
                .numero("1234")
                .depto("502")
                .ciudad("Santiago")
                .region("Región Metropolitana")
                .pais("Chile")
                .codigoPostal("7500000")
                .referencia("Casa con reja verde")
                .build();

        crearPedidoRequest = CrearPedidoRequest.builder()
                .empresaId(1L)
                .usuarioId(1L)
                .direccion(direccionRequest)
                .items(List.of(itemRequest))
                .notas("Entregar antes de las 18:00")
                .referenciaExterna("REF-123")
                .build();

        pedido = Pedido.builder()
                .id(1L)
                .empresa(empresa)
                .usuario(usuario)
                .direccion(direccionEntrega)
                .estado(EstadoPedido.PENDIENTE)
                .subtotal(new BigDecimal("59980"))
                .costoEnvio(BigDecimal.ZERO)
                .total(new BigDecimal("59980"))
                .notas("Entregar antes de las 18:00")
                .referenciaExterna("REF-123")
                .fechaCreacion(LocalDateTime.now())
                .items(new ArrayList<>())
                .historialEstados(new ArrayList<>())
                .build();
    }

    @Test
    void crearPedido_Success() {
        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresa));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));
        when(pedidoRepository.save(any(Pedido.class))).thenReturn(pedido);

        PedidoResponse response = pedidoService.crearPedido(crearPedidoRequest);

        assertNotNull(response);
        verify(empresaRepository).findById(1L);
        verify(usuarioRepository).findById(1L);
        verify(pedidoRepository).save(any(Pedido.class));
    }

    @Test
    void crearPedido_EmpresaNotFound_ThrowsException() {
        when(empresaRepository.findById(1L)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> pedidoService.crearPedido(crearPedidoRequest)
        );

        assertEquals("No existe empresa con ID: 1", exception.getMessage());
        verify(empresaRepository).findById(1L);
        verify(usuarioRepository, never()).findById(anyLong());
        verify(pedidoRepository, never()).save(any(Pedido.class));
    }

    @Test
    void crearPedido_UsuarioNotFound_ThrowsException() {
        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresa));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> pedidoService.crearPedido(crearPedidoRequest)
        );

        assertEquals("No existe usuario con ID: 1", exception.getMessage());
        verify(empresaRepository).findById(1L);
        verify(usuarioRepository).findById(1L);
        verify(pedidoRepository, never()).save(any(Pedido.class));
    }

    @Test
    void crearPedido_UsuarioNotFromEmpresa_ThrowsException() {
        Empresa otraEmpresa = Empresa.builder()
                .id(2L)
                .nombre("Otra Empresa")
                .build();

        Usuario otroUsuario = Usuario.builder()
                .id(2L)
                .empresa(otraEmpresa)
                .build();

        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresa));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(otroUsuario));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> pedidoService.crearPedido(crearPedidoRequest)
        );

        assertTrue(exception.getMessage().contains("no pertenecee a la empresa"));
        verify(empresaRepository).findById(1L);
        verify(usuarioRepository).findById(1L);
        verify(pedidoRepository, never()).save(any(Pedido.class));
    }

    @Test
    void cambiarEstado_ValidTransition_Success() {
        CambiarEstadoRequest request = CambiarEstadoRequest.builder()
                .nuevoEstado(EstadoPedido.CONFIRMADO)
                .motivo("Pago verificado")
                .build();

        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));
        when(pedidoRepository.save(any(Pedido.class))).thenReturn(pedido);

        PedidoResponse response = pedidoService.cambiarEstado(1L, request);

        assertNotNull(response);
        assertEquals(EstadoPedido.CONFIRMADO, pedido.getEstado());
        assertNotNull(pedido.getFechaConfirmacion());
        verify(pedidoRepository).findById(1L);
        verify(pedidoRepository).save(any(Pedido.class));
        verify(eventPublisher).publicarPedidoCreado(any(Pedido.class));
    }

    @Test
    void cambiarEstado_InvalidTransition_ThrowsException() {
        CambiarEstadoRequest request = CambiarEstadoRequest.builder()
                .nuevoEstado(EstadoPedido.ENTREGADO)
                .motivo("Test")
                .build();

        pedido.setEstado(EstadoPedido.PENDIENTE);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        TransaccionEstadoInvalidaException exception = assertThrows(
                TransaccionEstadoInvalidaException.class,
                () -> pedidoService.cambiarEstado(1L, request)
        );

        assertTrue(exception.getMessage().contains("PENDIENTE"));
        assertTrue(exception.getMessage().contains("ENTREGADO"));
        verify(pedidoRepository).findById(1L);
        verify(pedidoRepository, never()).save(any(Pedido.class));
        verify(eventPublisher, never()).publicarPedidoCreado(any(Pedido.class));
    }

    @Test
    void cambiarEstado_PedidoNotFound_ThrowsException() {
        CambiarEstadoRequest request = CambiarEstadoRequest.builder()
                .nuevoEstado(EstadoPedido.CONFIRMADO)
                .build();

        when(pedidoRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(
                PedidoNotFoundException.class,
                () -> pedidoService.cambiarEstado(1L, request)
        );

        verify(pedidoRepository).findById(1L);
        verify(pedidoRepository, never()).save(any(Pedido.class));
    }

    @Test
    void cambiarEstado_CancelFromPending_Success() {
        CambiarEstadoRequest request = CambiarEstadoRequest.builder()
                .nuevoEstado(EstadoPedido.CANCELADO)
                .motivo("Cliente solicitó cancelación")
                .build();

        pedido.setEstado(EstadoPedido.PENDIENTE);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));
        when(pedidoRepository.save(any(Pedido.class))).thenReturn(pedido);

        PedidoResponse response = pedidoService.cambiarEstado(1L, request);

        assertNotNull(response);
        assertEquals(EstadoPedido.CANCELADO, pedido.getEstado());
        verify(pedidoRepository).findById(1L);
        verify(pedidoRepository).save(any(Pedido.class));
        verify(eventPublisher, never()).publicarPedidoCreado(any(Pedido.class));
    }

    @Test
    void cambiarEstado_Entregado_SetsFechaEntrega() {
        CambiarEstadoRequest request = CambiarEstadoRequest.builder()
                .nuevoEstado(EstadoPedido.ENTREGADO)
                .motivo("Entregado exitosamente")
                .build();

        pedido.setEstado(EstadoPedido.EN_RUTA);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));
        when(pedidoRepository.save(any(Pedido.class))).thenReturn(pedido);

        PedidoResponse response = pedidoService.cambiarEstado(1L, request);

        assertNotNull(response);
        assertEquals(EstadoPedido.ENTREGADO, pedido.getEstado());
        assertNotNull(pedido.getFechaEntrega());
        verify(pedidoRepository).save(any(Pedido.class));
    }

    @Test
    void obtenerPedido_Success() {
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        PedidoResponse response = pedidoService.obtenerPedido(1L);

        assertNotNull(response);
        verify(pedidoRepository).findById(1L);
    }

    @Test
    void obtenerPedido_NotFound_ThrowsException() {
        when(pedidoRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(
                PedidoNotFoundException.class,
                () -> pedidoService.obtenerPedido(1L)
        );

        verify(pedidoRepository).findById(1L);
    }

    @Test
    void obtenerHistorialPorEmpresa_WithEstado_Success() {
        when(pedidoRepository.findByEmpresa_IdAndEstado(1L, EstadoPedido.CONFIRMADO))
                .thenReturn(List.of(pedido));

        List<PedidoResumenResponse> response = pedidoService.obtenerHistorialPorEmpresa(
                1L, EstadoPedido.CONFIRMADO, null, null);

        assertNotNull(response);
        assertEquals(1, response.size());
        verify(pedidoRepository).findByEmpresa_IdAndEstado(1L, EstadoPedido.CONFIRMADO);
    }

    @Test
    void obtenerHistorialPorEmpresa_WithDateRange_Success() {
        LocalDateTime desde = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime hasta = LocalDateTime.of(2024, 12, 31, 23, 59);

        when(pedidoRepository.findByEmpresa_IdAndFechaCreacionBetween(1L, desde, hasta))
                .thenReturn(List.of(pedido));

        List<PedidoResumenResponse> response = pedidoService.obtenerHistorialPorEmpresa(
                1L, null, desde, hasta);

        assertNotNull(response);
        assertEquals(1, response.size());
        verify(pedidoRepository).findByEmpresa_IdAndFechaCreacionBetween(1L, desde, hasta);
    }

    @Test
    void obtenerHistorialPorEmpresa_All_Success() {
        when(pedidoRepository.findByEmpresa_IdOrderByFechaCreacionDesc(1L))
                .thenReturn(List.of(pedido));

        List<PedidoResumenResponse> response = pedidoService.obtenerHistorialPorEmpresa(
                1L, null, null, null);

        assertNotNull(response);
        assertEquals(1, response.size());
        verify(pedidoRepository).findByEmpresa_IdOrderByFechaCreacionDesc(1L);
    }
}
