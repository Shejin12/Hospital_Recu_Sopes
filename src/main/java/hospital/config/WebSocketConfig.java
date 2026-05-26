package hospital.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuración de Spring para habilitar WebSockets vía STOMP.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Habilitamos un broker en memoria para los prefijos de destino de nuestros topics
        config.enableSimpleBroker("/topic");
        // Prefijo para mensajes enviados desde el cliente al servidor (si los hubiera)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Registramos el endpoint principal de conexión al WebSocket.
        // Habilitamos CORS (setAllowedOrigins("*")) para permitir peticiones del frontend.
        registry.addEndpoint("/ws-hospital").setAllowedOriginPatterns("*").withSockJS();
        
        // También exponemos el endpoint sin SockJS para clientes nativos u otros clientes web
        registry.addEndpoint("/ws-hospital").setAllowedOriginPatterns("*");
    }
}
