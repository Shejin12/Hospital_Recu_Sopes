package hospital.gestor;

import hospital.modelo.NivelTriaje;
import hospital.modelo.Paciente;
import hospital.servicio.WebSocketPublisher;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gestor central que administra los recursos del hospital de forma segura.
 * Convertido a @Service de Spring para inyectar dependencias y manejar Singleton.
 */
@Service
public class GestorRecursos {

    private final WebSocketPublisher publisher;

    // Recursos contables protegidos mediante Semáforos
    private final Semaphore salas = new Semaphore(10, true);
    private final Semaphore quirofanos = new Semaphore(3, true);
    private final Semaphore medicos = new Semaphore(8, true);
    private final Semaphore cirujanos = new Semaphore(4, true);
    private final Semaphore enfermeras = new Semaphore(10, true);
    private final Semaphore ventiladores = new Semaphore(5, true);
    private final Semaphore monitores = new Semaphore(8, true);

    // Cola de prioridad concurrente: garantiza orden por nivel de triaje
    private final PriorityBlockingQueue<Paciente> salaDeEspera = new PriorityBlockingQueue<>();

    // Lock para garantizar exclusión mutua al procesar la cola
    private final ReentrantLock lockAsignacion = new ReentrantLock(true);

    // Variables para Deadlock Controlado
    private Paciente pacienteDeadlockA = null;
    private Paciente pacienteDeadlockB = null;
    private CountDownLatch latchResolucionDeadlock = new CountDownLatch(1);
    private int idGanadorDeadlock = -1;

    @Autowired
    public GestorRecursos(WebSocketPublisher publisher) {
        this.publisher = publisher;
    }

    public void logYEnviar(String mensaje) {
        System.out.println(mensaje);
        publisher.enviarEvento("CONSOLE_LOG", mensaje, null);
    }

    /**
     * Ingresa al paciente en la cola de espera y dispara el procesamiento de la cola.
     */
    public void solicitarRecursos(Paciente paciente) {
        logYEnviar("[GESTOR] Paciente " + paciente.getId() + " ingresa a la sala de espera.");
        salaDeEspera.put(paciente);
        
        // Emitir evento WebSocket para actualizar la cola en el frontend
        Map<String, Object> data = new HashMap<>();
        data.put("idPaciente", paciente.getId());
        data.put("triaje", paciente.getNivel().name());
        data.put("prioridad", paciente.getNivel().getPrioridad());
        publisher.enviarEvento("NUEVO_PACIENTE", "El paciente " + paciente.getId() + " ingresó a espera.", data);

        procesarCola();
    }

    /**
     * Implementa el escenario de interbloqueo controlado para demostración.
     * El Paciente A adquiere un Cirujano y espera un Ventilador.
     * El Paciente B adquiere un Ventilador y espera un Cirujano.
     * Ambos quedan bloqueados → deadlock detectado → frontend elige ganador.
     */
    public void forzarDeadlock(Paciente paciente) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ex) {
            Logger.getLogger(GestorRecursos.class.getName()).log(Level.SEVERE, null, ex);
        }
        logYEnviar("[DEADLOCK-DEMO] Paciente " + paciente.getId() + " entra a modo interbloqueo.");
        
        boolean esPacienteA = false;
        
        synchronized (this) {
            if (pacienteDeadlockA == null) {
                pacienteDeadlockA = paciente;
                esPacienteA = true;
            } else if (pacienteDeadlockB == null) {
                pacienteDeadlockB = paciente;
            } else {
                // Ya hay dos pacientes en deadlock, este entra a cola normal
                solicitarRecursos(paciente);
                return;
            }
        }

        try {
            if (esPacienteA) {
                // Paciente A: adquiere Cirujano, luego intenta adquirir Ventilador
                cirujanos.acquire(1);
                Thread.sleep(1000);
                boolean obtuvoVentilador = ventiladores.tryAcquire(1, 3, TimeUnit.SECONDS);
                
                if (!obtuvoVentilador) {
                    reportarDeadlock(paciente.getId(), "Cirujano", "Ventilador");
                    latchResolucionDeadlock.await();
                    aplicarResolucionDeadlock(paciente, true);
                } else {
                    paciente.notificarRecursosAsignados();
                }

            } else {
                // Paciente B: adquiere Ventilador, luego intenta adquirir Cirujano
                ventiladores.acquire(1);
                Thread.sleep(1000);
                boolean obtuvoCirujano = cirujanos.tryAcquire(1, 3, TimeUnit.SECONDS);
                
                if (!obtuvoCirujano) {
                    reportarDeadlock(paciente.getId(), "Ventilador", "Cirujano");
                    latchResolucionDeadlock.await();
                    aplicarResolucionDeadlock(paciente, false);
                } else {
                    paciente.notificarRecursosAsignados();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Emite un evento WebSocket avisando al frontend del deadlock detectado.
     */
    private void reportarDeadlock(int idPaciente, String recursoRetenido, String recursoFaltante) {
        System.err.println(">>>> [ALERTA] Paciente " + idPaciente + " detectó DEADLOCK.");
        Map<String, Object> data = new HashMap<>();
        data.put("idPaciente", idPaciente);
        data.put("recursoRetenido", recursoRetenido);
        data.put("recursoFaltante", recursoFaltante);
        publisher.enviarEvento("DEADLOCK_DETECTADO", 
            "El paciente " + idPaciente + " retiene " + recursoRetenido + " pero necesita " + recursoFaltante, data);
    }

    /**
     * Aplica la resolución del deadlock: el ganador obtiene los recursos faltantes
     * y el perdedor libera lo que tenía y regresa a la cola normal.
     */
    private void aplicarResolucionDeadlock(Paciente paciente, boolean esPacienteA) throws InterruptedException {
        if (paciente.getId() == idGanadorDeadlock) {
            logYEnviar("[RESOLUCIÓN] Paciente " + paciente.getId() + " GANÓ.");
            publisher.enviarEvento("DEADLOCK_RESUELTO", "Paciente " + paciente.getId() + " fue elegido ganador.", null);
            
            Thread.sleep(500); 
            if (esPacienteA) ventiladores.acquire(1);
            else cirujanos.acquire(1);
            
            salas.acquire(1);
            quirofanos.acquire(1);
            enfermeras.acquire(2);
            monitores.acquire(1);
            
            paciente.notificarRecursosAsignados();
            publisher.enviarEvento("ATENCION_INICIADA", "Paciente " + paciente.getId() + " inició atención (Deadlock superado).", paciente.getId());
        } else {
            logYEnviar("[RESOLUCIÓN] Paciente " + paciente.getId() + " PERDIÓ.");
            if (esPacienteA) cirujanos.release(1);
            else ventiladores.release(1);
            
            // El paciente perdedor regresa a la cola de espera normal
            solicitarRecursos(paciente); 
        }

        synchronized (this) {
            // Reiniciar estado para futuras simulaciones de deadlock
            if (esPacienteA) {
                pacienteDeadlockA = null;
            } else {
                pacienteDeadlockB = null;
            }
            if (pacienteDeadlockA == null && pacienteDeadlockB == null) {
                idGanadorDeadlock = -1;
                latchResolucionDeadlock = new CountDownLatch(1);
            }
        }
    }

    /**
     * Recibe el ID del paciente ganador desde el frontend y desbloquea el latch.
     */
    public void resolverDeadlock(String idPacienteElegido) {
        try {
            this.idGanadorDeadlock = Integer.parseInt(idPacienteElegido);
            latchResolucionDeadlock.countDown();
        } catch (NumberFormatException e) {
            System.err.println("ID de paciente inválido para resolver deadlock: " + idPacienteElegido);
        }
    }

    /**
     * Libera todos los recursos del paciente y vuelve a procesar la cola.
     */
    public void liberarRecursos(Paciente paciente) {
        NivelTriaje nivel = paciente.getNivel();
        
        logYEnviar("[LIBERANDO] " + paciente.toString() + " devuelve sus recursos.");
        publisher.enviarEvento("RECURSOS_LIBERADOS", "Paciente " + paciente.getId() + " liberó sus recursos.", paciente.getId());

        if (nivel.getSalasRequeridas() > 0) salas.release(nivel.getSalasRequeridas());
        if (nivel.getQuirofanosRequeridos() > 0) quirofanos.release(nivel.getQuirofanosRequeridos());
        if (nivel.getMedicosRequeridos() > 0) medicos.release(nivel.getMedicosRequeridos());
        if (nivel.getCirujanosRequeridos() > 0) cirujanos.release(nivel.getCirujanosRequeridos());
        if (nivel.getEnfermerasRequeridas() > 0) enfermeras.release(nivel.getEnfermerasRequeridas());
        if (nivel.getVentiladoresRequeridos() > 0) ventiladores.release(nivel.getVentiladoresRequeridos());
        if (nivel.getMonitoresRequeridos() > 0) monitores.release(nivel.getMonitoresRequeridos());

        procesarCola();
    }

    /**
     * Procesa la cola de espera intentando asignar recursos a cada paciente.
     *
     * BUG CORREGIDO: La versión anterior usaba break() al encontrar el primer paciente
     * sin recursos, bloqueando permanentemente a TODOS los pacientes detrás de él,
     * incluso si un paciente de menor prioridad sí podía ser atendido con los recursos
     * disponibles (ej: un Nivel 3 que solo necesita sala+médico mientras esperaba un
     * Nivel 1 que requería quirófano ocupado).
     *
     * SOLUCIÓN: Se drena toda la cola, se intenta atender a cada paciente en orden
     * de prioridad, y los que no pueden ser atendidos se reinsertan para mantener
     * el ordenamiento correcto de la PriorityBlockingQueue.
     */
    private void procesarCola() {
        lockAsignacion.lock();
        try {
            List<Paciente> noAtendidos = new ArrayList<>();
            Paciente pacienteEnEspera;

            // Drain completo de la cola para evaluar a todos los pacientes
            while ((pacienteEnEspera = salaDeEspera.poll()) != null) {
                NivelTriaje nivel = pacienteEnEspera.getNivel();

                if (hayRecursosDisponibles(nivel)) {
                    try {
                        // Adquirir todos los recursos requeridos por este nivel
                        if (nivel.getSalasRequeridas() > 0) salas.acquire(nivel.getSalasRequeridas());
                        if (nivel.getQuirofanosRequeridos() > 0) quirofanos.acquire(nivel.getQuirofanosRequeridos());
                        if (nivel.getMedicosRequeridos() > 0) medicos.acquire(nivel.getMedicosRequeridos());
                        if (nivel.getCirujanosRequeridos() > 0) cirujanos.acquire(nivel.getCirujanosRequeridos());
                        if (nivel.getEnfermerasRequeridas() > 0) enfermeras.acquire(nivel.getEnfermerasRequeridas());
                        if (nivel.getVentiladoresRequeridos() > 0) ventiladores.acquire(nivel.getVentiladoresRequeridos());
                        if (nivel.getMonitoresRequeridos() > 0) monitores.acquire(nivel.getMonitoresRequeridos());
                        
                        logYEnviar("[ASIGNADO] Recursos entregados a " + pacienteEnEspera.toString());
                        
                        Map<String, Object> data = new HashMap<>();
                        data.put("idPaciente", pacienteEnEspera.getId());
                        data.put("triaje", nivel.name());
                        data.put("prioridad", nivel.getPrioridad());
                        data.put("tiempoAtencion", pacienteEnEspera.getTiempoAtencionMs());
                        publisher.enviarEvento("RECURSOS_ASIGNADOS", "Paciente " + pacienteEnEspera.getId() + " inició atención.", data);

                        pacienteEnEspera.notificarRecursosAsignados();
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // Si fue interrumpido durante la adquisición, reinsertar en cola
                        noAtendidos.add(pacienteEnEspera);
                    }
                } else {
                    // No hay recursos suficientes ahora: guardar para reinsertar
                    noAtendidos.add(pacienteEnEspera);
                }
            }

            // Reinsertar todos los pacientes que no pudieron ser atendidos.
            // PriorityBlockingQueue reordenará según Comparable de Paciente.
            salaDeEspera.addAll(noAtendidos);

        } finally {
            lockAsignacion.unlock();
        }
    }

    /**
     * Verifica de forma atómica si hay suficientes permisos en cada semáforo
     * para atender al paciente con el nivel de triaje dado.
     */
    private boolean hayRecursosDisponibles(NivelTriaje nivel) {
        return salas.availablePermits() >= nivel.getSalasRequeridas() &&
               quirofanos.availablePermits() >= nivel.getQuirofanosRequeridos() &&
               medicos.availablePermits() >= nivel.getMedicosRequeridos() &&
               cirujanos.availablePermits() >= nivel.getCirujanosRequeridos() &&
               enfermeras.availablePermits() >= nivel.getEnfermerasRequeridas() &&
               ventiladores.availablePermits() >= nivel.getVentiladoresRequeridos() &&
               monitores.availablePermits() >= nivel.getMonitoresRequeridos();
    }
}
