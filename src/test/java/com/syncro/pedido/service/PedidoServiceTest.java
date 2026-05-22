package com.syncro.pedido.service;

import com.syncro.pedido.dto.request.CambiarEstadoRequest;
import com.syncro.pedido.dto.request.CrearPedidoRequest;
import com.syncro.pedido.dto.request.DireccionRequest;
import com.syncro.pedido.dto.request.ItemPedidoRequest;
import com.syncro.pedido.dto.response.PedidoResponse;
import com.syncro.pedido.dto.response.PedidoResumenResponse;
import com.syncro.pedido.event.PedidoEventPublisher;
import com.syncro.pedido.exception.PedidoNotFoundException;
import com.syncro.pedido.exception.TransaccionEstadoInvalidaException;
import com.syncro.pedido.model.*;
import com.syncro.pedido.repository.EmpresaRepository;
import com.syncro.pedido.repository.PedidoRepository;
import com.syncro.pedido.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PedidoService - Tests Unitarios")
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

    private Empresa empresaMock;
    private Usuario usuarioMock;
    private Pedido pedidoMock;

    @BeforeEach
    void setUp() {
        empresaMock = Empresa.builder()
                .id(1L)
                .nombre("PYME Demo SpA")
                .rut("76.543.210-K")
                .email("admin@pyme-demo.cl")
                .activo(true)
                .fechaCreacion(LocalDateTime.now())
                .build();

        usuarioMock = Usuario.builder()
                .id(1L)
                .nombre("Oriana Solorzano")
                .email("oriana@pyme-demo.cl")
                .password("$2a$12$hash")
                .empresa(empresaMock)
                .rol(Rol.ADMIN)
                .activo(true)
                .build();

        DireccionEntrega direccionMock = DireccionEntrega.builder()
                .id(1L)
                .calle("Av. Providencia")
                .numero("1234")
                .ciudad("Santiago")
                .region("Región Metropolitana")
                .pais("Chile")
                .build();

        ItemPedido itemMock = ItemPedido.builder()
                .id(1L)
                .sku("ELEC-001")
                .nombre("Audífonos Bluetooth")
                .cantidad(2)
                .precioUnitario(new BigDecimal("29990"))
                .build();

        pedidoMock = Pedido.builder()
                .id(1L)
                .empresa(empresaMock)
                .usuario(usuarioMock)
                .direccion(direccionMock)
                .estado(EstadoPedido.PENDIENTE)
                .subtotal(new BigDecimal("59980"))
                .costoEnvio(BigDecimal.ZERO)
                .total(new BigDecimal("59980"))
                .eventoPublicado(false)
                .fechaCreacion(LocalDateTime.now())
                .fechaActualizacion(LocalDateTime.now())
                .items(new ArrayList<>(List.of(itemMock)))
                .historialEstados(new ArrayList<>())
                .build();

        itemMock.setPedido(pedidoMock);
    }

    // =========================================================================
    // crearPedido
    // =========================================================================
    @Test
    @DisplayName("crearPedido - debe crear pedido correctamente con datos válidos")
    void crearPedido_conDatosValidos_retornaPedidoResponse() {
        CrearPedidoRequest request = buildCrearPedidoRequest();

        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresaMock));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioMock));
        when(pedidoRepository.save(any(Pedido.class))).thenAnswer(inv -> {
            Pedido p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        PedidoResponse response = pedidoService.crearPedido(request);

        assertThat(response).isNotNull();
        assertThat(response.getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
        assertThat(response.getEmpresa().getId()).isEqualTo(1L);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getSubtotal()).isEqualByComparingTo("59980.00");
        verify(pedidoRepository, times(1)).save(any(Pedido.class));
    }

    @Test
    @DisplayName("crearPedido - empresa inexistente lanza IllegalArgumentException")
    void crearPedido_empresaNoExiste_lanzaIllegalArgumentException() {
        CrearPedidoRequest request = buildCrearPedidoRequest();
        request.setEmpresaId(99L);
        when(empresaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pedidoService.crearPedido(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");

        verify(pedidoRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearPedido - usuario inexistente lanza IllegalArgumentException")
    void crearPedido_usuarioNoExiste_lanzaIllegalArgumentException() {
        CrearPedidoRequest request = buildCrearPedidoRequest();
        request.setUsuarioId(99L);
        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresaMock));
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pedidoService.crearPedido(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("crearPedido - usuario de otra empresa lanza IllegalArgumentException")
    void crearPedido_usuarioDeOtraEmpresa_lanzaIllegalArgumentException() {
        Empresa otraEmpresa = Empresa.builder()
                .id(2L).nombre("Otra").rut("11.111.111-1")
                .email("otra@empresa.cl").activo(true).build();
        Usuario usuarioOtro = Usuario.builder()
                .id(2L).empresa(otraEmpresa).email("otro@empresa.cl")
                .password("hash").rol(Rol.OPERADOR).activo(true).build();

        CrearPedidoRequest request = buildCrearPedidoRequest();
        request.setUsuarioId(2L);

        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresaMock));
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(usuarioOtro));

        assertThatThrownBy(() -> pedidoService.crearPedido(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pertenec");
    }

    @Test
    @DisplayName("crearPedido - múltiples items calcula subtotal correctamente")
    void crearPedido_multiplesItems_calculaSubtotalCorrectamente() {
        CrearPedidoRequest request = buildCrearPedidoRequest();
        request.getItems().add(ItemPedidoRequest.builder()
                .sku("ELEC-002").nombre("Cable USB-C")
                .cantidad(3).precioUnitario(new BigDecimal("4990")).build());

        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresaMock));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioMock));
        when(pedidoRepository.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));

        PedidoResponse response = pedidoService.crearPedido(request);

        // 2*29990 + 3*4990 = 59980 + 14970 = 74950
        assertThat(response.getSubtotal()).isEqualByComparingTo("74950.00");
    }

    @Test
    @DisplayName("crearPedido - sin país asigna Chile por defecto")
    void crearPedido_sinPais_asignaChilePorDefecto() {
        CrearPedidoRequest request = buildCrearPedidoRequest();
        request.getDireccion().setPais(null);

        when(empresaRepository.findById(1L)).thenReturn(Optional.of(empresaMock));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuarioMock));
        when(pedidoRepository.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));

        PedidoResponse response = pedidoService.crearPedido(request);

        assertThat(response.getDireccion().getPais()).isEqualTo("Chile");
    }

    // =========================================================================
    // obtenerPedido
    // =========================================================================
    @Test
    @DisplayName("obtenerPedido - id existente retorna PedidoResponse")
    void obtenerPedido_idExistente_retornaPedidoResponse() {
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedidoMock));

        PedidoResponse response = pedidoService.obtenerPedido(1L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
    }

    @Test
    @DisplayName("obtenerPedido - id inexistente lanza PedidoNotFoundException")
    void obtenerPedido_idInexistente_lanzaPedidoNotFoundException() {
        when(pedidoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pedidoService.obtenerPedido(99L))
                .isInstanceOf(PedidoNotFoundException.class)
                .hasMessageContaining("99");
    }

    // =========================================================================
    // cambiarEstado
    // =========================================================================
    @Test
    @DisplayName("cambiarEstado - PENDIENTE a CONFIRMADO publica evento RabbitMQ")
    void cambiarEstado_pendienteAConfirmado_publicaEvento() {
        mockSecurityContext("oriana@pyme-demo.cl");
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedidoMock));
        when(usuarioRepository.findByEmail("oriana@pyme-demo.cl")).thenReturn(Optional.of(usuarioMock));
        when(pedidoRepository.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));

        CambiarEstadoRequest request = new CambiarEstadoRequest(EstadoPedido.CONFIRMADO, "Pago OK");

        PedidoResponse response = pedidoService.cambiarEstado(1L, request);

        assertThat(response.getEstado()).isEqualTo(EstadoPedido.CONFIRMADO);
        assertThat(response.getFechaConfirmacion()).isNotNull();
        verify(eventPublisher, times(1)).publicarPedidoCreado(any(Pedido.class));
    }

    @Test
    @DisplayName("cambiarEstado - transición inválida lanza TransaccionEstadoInvalidaException")
    void cambiarEstado_transicionInvalida_lanzaExcepcion() {
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedidoMock));

        CambiarEstadoRequest request = new CambiarEstadoRequest(EstadoPedido.EN_RUTA, null);

        assertThatThrownBy(() -> pedidoService.cambiarEstado(1L, request))
                .isInstanceOf(TransaccionEstadoInvalidaException.class)
                .hasMessageContaining("PENDIENTE")
                .hasMessageContaining("EN_RUTA");

        verify(eventPublisher, never()).publicarPedidoCreado(any());
    }

    @Test
    @DisplayName("cambiarEstado - PENDIENTE a CANCELADO no publica evento")
    void cambiarEstado_pendienteACancelado_noPublicaEvento() {
        mockSecurityContext("oriana@pyme-demo.cl");
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedidoMock));
        when(usuarioRepository.findByEmail("oriana@pyme-demo.cl")).thenReturn(Optional.of(usuarioMock));
        when(pedidoRepository.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));

        CambiarEstadoRequest request = new CambiarEstadoRequest(EstadoPedido.CANCELADO, "Cliente canceló");

        PedidoResponse response = pedidoService.cambiarEstado(1L, request);

        assertThat(response.getEstado()).isEqualTo(EstadoPedido.CANCELADO);
        verify(eventPublisher, never()).publicarPedidoCreado(any());
    }

    @Test
    @DisplayName("cambiarEstado - pedido ENTREGADO no puede cambiar de estado")
    void cambiarEstado_pedidoEntregado_lanzaExcepcion() {
        pedidoMock.setEstado(EstadoPedido.ENTREGADO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedidoMock));

        CambiarEstadoRequest request = new CambiarEstadoRequest(EstadoPedido.CANCELADO, null);

        assertThatThrownBy(() -> pedidoService.cambiarEstado(1L, request))
                .isInstanceOf(TransaccionEstadoInvalidaException.class);
    }

    @Test
    @DisplayName("cambiarEstado - EN_RUTA a ENTREGADO registra fecha de entrega")
    void cambiarEstado_aEntregado_registraFechaEntrega() {
        mockSecurityContext("oriana@pyme-demo.cl");
        pedidoMock.setEstado(EstadoPedido.EN_RUTA);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedidoMock));
        when(usuarioRepository.findByEmail("oriana@pyme-demo.cl")).thenReturn(Optional.of(usuarioMock));
        when(pedidoRepository.save(any(Pedido.class))).thenAnswer(inv -> inv.getArgument(0));

        CambiarEstadoRequest request = new CambiarEstadoRequest(EstadoPedido.ENTREGADO, "Entregado");

        PedidoResponse response = pedidoService.cambiarEstado(1L, request);

        assertThat(response.getEstado()).isEqualTo(EstadoPedido.ENTREGADO);
        assertThat(response.getFechaEntrega()).isNotNull();
    }

    @Test
    @DisplayName("cambiarEstado - pedido inexistente lanza PedidoNotFoundException")
    void cambiarEstado_pedidoNoExiste_lanzaException() {
        when(pedidoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pedidoService.cambiarEstado(99L,
                new CambiarEstadoRequest(EstadoPedido.CONFIRMADO, null)))
                .isInstanceOf(PedidoNotFoundException.class);
    }

    // =========================================================================
    // obtenerHistorialPorEmpresa
    // =========================================================================
    @Test
    @DisplayName("obtenerHistorial - sin filtros retorna todos los pedidos")
    void obtenerHistorial_sinFiltros_retornaLista() {
        when(pedidoRepository.findByEmpresa_IdOrderByFechaCreacionDesc(1L))
                .thenReturn(List.of(pedidoMock));

        List<PedidoResumenResponse> resultado
                = pedidoService.obtenerHistorialPorEmpresa(1L, null, null, null);

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
    }

    @Test
    @DisplayName("obtenerHistorial - con estado usa repositorio correcto")
    void obtenerHistorial_conFiltroEstado_usaRepositorioCorrecto() {
        when(pedidoRepository.findByEmpresa_IdAndEstado(1L, EstadoPedido.CONFIRMADO))
                .thenReturn(List.of());

        List<PedidoResumenResponse> resultado
                = pedidoService.obtenerHistorialPorEmpresa(1L, EstadoPedido.CONFIRMADO, null, null);

        assertThat(resultado).isEmpty();
        verify(pedidoRepository).findByEmpresa_IdAndEstado(1L, EstadoPedido.CONFIRMADO);
        verify(pedidoRepository, never()).findByEmpresa_IdOrderByFechaCreacionDesc(any());
    }

    @Test
    @DisplayName("obtenerHistorial - con rango de fechas usa repositorio correcto")
    void obtenerHistorial_conFiltroFechas_usaRepositorioCorrecto() {
        LocalDateTime desde = LocalDateTime.now().minusDays(7);
        LocalDateTime hasta = LocalDateTime.now();
        when(pedidoRepository.findByEmpresa_IdAndFechaCreacionBetween(1L, desde, hasta))
                .thenReturn(List.of(pedidoMock));

        List<PedidoResumenResponse> resultado
                = pedidoService.obtenerHistorialPorEmpresa(1L, null, desde, hasta);

        assertThat(resultado).hasSize(1);
        verify(pedidoRepository).findByEmpresa_IdAndFechaCreacionBetween(1L, desde, hasta);
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private CrearPedidoRequest buildCrearPedidoRequest() {
        DireccionRequest direccion = DireccionRequest.builder()
                .calle("Av. Providencia").numero("1234")
                .ciudad("Santiago").region("Región Metropolitana").pais("Chile")
                .build();

        ItemPedidoRequest item = ItemPedidoRequest.builder()
                .sku("ELEC-001").nombre("Audífonos Bluetooth")
                .cantidad(2).precioUnitario(new BigDecimal("29990"))
                .build();

        return CrearPedidoRequest.builder()
                .empresaId(1L).usuarioId(1L)
                .notas("Test")
                .direccion(direccion)
                .items(new ArrayList<>(List.of(item)))
                .build();
    }

    private void mockSecurityContext(String email) {
        Authentication auth = mock(Authentication.class);
        SecurityContext ctx = mock(SecurityContext.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn(email);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }
}
