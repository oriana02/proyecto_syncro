package com.syncro.pedido.event;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.syncro.pedido.config.RabbitMQConfig;
import com.syncro.pedido.model.Pedido;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class PedidoEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publicarPedidoCreado(Pedido pedido) {
        // Construir el evento con los datos que necesita inventario
        PedidoCreadoEvent evento = PedidoCreadoEvent.builder()
                .pedidoId(pedido.getId())
                .empresaId(pedido.getEmpresa().getId())
                .items(pedido.getItems().stream()
                        .map(item -> PedidoCreadoEvent.ItemEvento.builder()
                        .sku(item.getSku())
                        .cantidad(item.getCantidad())
                        .precioUnitario(item.getPrecioUnitario())
                        .build())
                        .toList())
                .build();

        // Publicar al exchange — RabbitMQ lo distribuye a inventario.sincronizar y envio.generar
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, "", evento);

        log.info("Evento pedido.creado publicado para pedido ID={}", pedido.getId());
    }

}
