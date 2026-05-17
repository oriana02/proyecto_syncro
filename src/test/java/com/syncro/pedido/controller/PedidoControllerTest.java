package com.syncro.pedido.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.syncro.pedido.dto.request.CambiarEstadoRequest;
import com.syncro.pedido.dto.request.CrearPedidoRequest;
import com.syncro.pedido.dto.request.DireccionRequest;
import com.syncro.pedido.dto.request.ItemPedidoRequest;
import com.syncro.pedido.dto.response.PedidoResponse;
import com.syncro.pedido.dto.response.PedidoResumenResponse;
import com.syncro.pedido.exception.PedidoNotFoundException;
import com.syncro.pedido.exception.TransaccionEstadoInvalidaException;
import com.syncro.pedido.model.EstadoPedido;
import com.syncro.pedido.service.PedidoService;

@ExtendWith(MockitoExtension.class)
class PedidoControllerTest {

    @Mock
    private PedidoService pedidoService;

    @InjectMocks
    private PedidoController pedidoController;

    private CrearPedidoRequest crearPedidoRequest;
    private CambiarEstadoRequest cambiarEstadoRequest;
    private PedidoResponse pedidoResponse;
    private PedidoResumenResponse pedidoResumenResponse;

    @BeforeEach
    void setUp() {
        DireccionRequest direccionRequest = DireccionRequest.builder()
                .calle("Av. Providencia")
                .numero("1234")
                .ciudad("Santiago")
                .region("Región Metropolitana")
                .pais("Chile")
                .build();

        ItemPedidoRequest itemRequest = ItemPedidoRequest.builder()
                .sku("ELEC-001")
                .nombre("Audífonos BT")
                .cantidad(2)
                .precioUnitario(new BigDecimal("29990"))
                .build();

        crearPedidoRequest = CrearPedidoRequest.builder()
                .empresaId(1L)
                .usuarioId(1L)
                .direccion(direccionRequest)
                .items(List.of(itemRequest))
                .notas("Entregar antes de las 18:00")
                .build();

        cambiarEstadoRequest = CambiarEstadoRequest.builder()
                .nuevoEstado(EstadoPedido.CONFIRMADO)
                .motivo("Pago verificado")
                .build();

        pedidoResponse = PedidoResponse.builder()
                .id(1L)
                .estado(EstadoPedido.CONFIRMADO)
                .subtotal(new BigDecimal("59980"))
                .total(new BigDecimal("59980"))
                .fechaCreacion(LocalDateTime.now())
                .build();

        pedidoResumenResponse = PedidoResumenResponse.builder()
                .id(1L)
                .empresaNombre("Test Empresa")
                .usuarioNombre("Test User")
                .estado(EstadoPedido.CONFIRMADO)
                .total(new BigDecimal("59980"))
                .totalItems(1)
                .fechaCreacion(LocalDateTime.now())
                .build();
    }

    @Test
    void crearPedido_Success_Returns201() {
        when(pedidoService.crearPedido(any(CrearPedidoRequest.class))).thenReturn(pedidoResponse);

        ResponseEntity<PedidoResponse> response = pedidoController.crearPedido(crearPedidoRequest);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1L, response.getBody().getId());
        assertEquals(EstadoPedido.CONFIRMADO, response.getBody().getEstado());
    }

    @Test
    void obtenerPedido_Success_Returns200() {
        when(pedidoService.obtenerPedido(1L)).thenReturn(pedidoResponse);

        ResponseEntity<PedidoResponse> response = pedidoController.obtenerPedido(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1L, response.getBody().getId());
        assertEquals(EstadoPedido.CONFIRMADO, response.getBody().getEstado());
    }

    @Test
    void obtenerPedido_NotFound_ThrowsException() {
        when(pedidoService.obtenerPedido(1L))
                .thenThrow(new PedidoNotFoundException(1L));

        assertThrows(PedidoNotFoundException.class, () -> {
            pedidoController.obtenerPedido(1L);
        });
    }

    @Test
    void cambiarEstado_Success_Returns200() {
        when(pedidoService.cambiarEstado(anyLong(), any(CambiarEstadoRequest.class)))
                .thenReturn(pedidoResponse);

        ResponseEntity<PedidoResponse> response = pedidoController.cambiarEstado(1L, cambiarEstadoRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(EstadoPedido.CONFIRMADO, response.getBody().getEstado());
    }

    @Test
    void cambiarEstado_InvalidTransition_ThrowsException() {
        when(pedidoService.cambiarEstado(anyLong(), any(CambiarEstadoRequest.class)))
                .thenThrow(new TransaccionEstadoInvalidaException("PENDIENTE", "ENTREGADO"));

        assertThrows(TransaccionEstadoInvalidaException.class, () -> {
            pedidoController.cambiarEstado(1L, cambiarEstadoRequest);
        });
    }

    @Test
    void obtenerHistorial_Success_Returns200() {
        when(pedidoService.obtenerHistorialPorEmpresa(anyLong(), any(), any(), any()))
                .thenReturn(List.of(pedidoResumenResponse));

        ResponseEntity<List<PedidoResumenResponse>> response = pedidoController.obtenerHistorial(1L, null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(1L, response.getBody().get(0).getId());
        assertEquals("Test Empresa", response.getBody().get(0).getEmpresaNombre());
    }
}
