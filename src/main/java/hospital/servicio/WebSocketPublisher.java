package hospital.servicio;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Servicio encargado de enviar mensajes al Frontend mediante WebSockets.
 */
@Service
public class WebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Envía un evento genérico en formato JSON al tópico correspondiente.
     * @param tipoEvento Nombre o tipo del evento (ej. "NUEVO_PACIENTE", "RECURSOS_ASIGNADOS")
     * @param mensaje Descripción del evento para el log del frontend
     * @param datos Adicionales o payload completo (puede ser nulo)
     */
    public void enviarEvento(String tipoEvento, String mensaje, Object datos) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tipo", tipoEvento);
        payload.put("mensaje", mensaje);
        payload.put("timestamp", System.currentTimeMillis());
        if (datos != null) {
            payload.put("datos", datos);
        }

        // Publicamos el mensaje en el canal público de eventos
        messagingTemplate.convertAndSend("/topic/eventos", payload);
    }
}
