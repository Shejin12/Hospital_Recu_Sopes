package hospital;

import hospital.gestor.GestorRecursos;
import hospital.modelo.NivelTriaje;
import hospital.modelo.Paciente;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Clase principal que inicializa el servidor Spring Boot y lanza la simulación en hilos asíncronos.
 */
@SpringBootApplication
@EnableAsync
public class Simulador implements CommandLineRunner {

    private final GestorRecursos gestor;

    @Autowired
    public Simulador(GestorRecursos gestor) {
        this.gestor = gestor;
    }

    public static void main(String[] args) {
        SpringApplication.run(Simulador.class, args);
    }

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("SimuladorAsync-");
        executor.initialize();
        return executor;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("==================================================");
        System.out.println(" INICIANDO SERVIDOR WEB Y SIMULACIÓN HOSPITALARIA ");
        System.out.println("==================================================");

        // Lanzar la simulación en un hilo nativo nuevo para no bloquear a Spring
        new Thread(this::iniciarSimulacionInfinita, "Hilo-SimulacionPrincipal").start();
    }

    private void iniciarSimulacionInfinita() {
        // Bucle infinito para llegada de pacientes
        System.out.println("[SISTEMA] Iniciando llegada de pacientes en paralelo...");
        while (true) {
            if (!gestor.isSimulacionPausada()) {
                Paciente nuevoPaciente = new Paciente(gestor);
                new Thread(nuevoPaciente).start();
            }
            
            try {
                int pausaLlegada = 2000 + (int) (Math.random() * 3000); // Pausa ligeramente más larga para que no ahogue el websocket en la demo
                Thread.sleep(pausaLlegada);
            } catch (InterruptedException e) {
                System.err.println("Simulación interrumpida.");
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
