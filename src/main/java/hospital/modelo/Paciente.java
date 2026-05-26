package hospital.modelo;

import hospital.gestor.GestorRecursos;
import java.util.concurrent.CountDownLatch;

/**
 * Representa a un paciente en el sistema. 
 * Implementa Runnable para ser ejecutado como un hilo independiente.
 * Implementa Comparable para poder ser ordenado en la PriorityBlockingQueue.
 */
public class Paciente implements Runnable, Comparable<Paciente> {

    private static int contadorId = 1;
    private final int id;
    private final NivelTriaje nivel;
    private final GestorRecursos gestor;
    private final int tiempoAtencionMs;
    
    // Sincronizador para pausar el hilo mientras espera en la cola
    private final CountDownLatch latchEspera;

    public Paciente(GestorRecursos gestor) {
        this.id = generarId();
        this.nivel = NivelTriaje.generarAleatorio();
        this.gestor = gestor;
        this.tiempoAtencionMs = calcularTiempoPorTriaje(this.nivel);
        this.latchEspera = new CountDownLatch(1);
    }

    public Paciente(GestorRecursos gestor, NivelTriaje nivelFijo) {
        this.id = generarId();
        this.nivel = nivelFijo;
        this.gestor = gestor;
        this.tiempoAtencionMs = calcularTiempoPorTriaje(this.nivel);
        this.latchEspera = new CountDownLatch(1);
    }

    private int calcularTiempoPorTriaje(NivelTriaje n) {
        // Asignamos tiempos más largos y acordes a la prioridad (en milisegundos)
        switch (n.getPrioridad()) {
            case 1: return 15000 + (int)(Math.random() * 5000); // 15 a 20 seg
            case 2: return 10000 + (int)(Math.random() * 5000); // 10 a 15 seg
            case 3: return 7000 + (int)(Math.random() * 3000);  // 7 a 10 seg
            case 4: return 4000 + (int)(Math.random() * 3000);  // 4 a 7 seg
            case 5: return 2000 + (int)(Math.random() * 2000);  // 2 a 4 seg
            default: return 5000;
        }
    }

    public int getTiempoAtencionMs() {
        return tiempoAtencionMs;
    }

    private synchronized static int generarId() {
        return contadorId++;
    }

    public int getId() {
        return id;
    }

    public NivelTriaje getNivel() {
        return nivel;
    }

    /**
     * Método que llama el Gestor de Recursos para notificar al paciente que
     * sus recursos han sido asignados y puede continuar su atención.
     */
    public void notificarRecursosAsignados() {
        latchEspera.countDown();
    }

    private boolean modoDeadlock = false;

    public void setModoDeadlock(boolean modoDeadlock) {
        this.modoDeadlock = modoDeadlock;
    }

    @Override
    public void run() {
        try {
            gestor.logYEnviar("[INGRESO] Paciente " + id + " llega con " + nivel);
            
            if (modoDeadlock && nivel.getPrioridad() == 1) {
                // Ir por la ruta del deadlock controlado
                gestor.forzarDeadlock(this);
            } else {
                // 1. Solicitar recursos al gestor
                gestor.solicitarRecursos(this);
                
                // 2. Esperar hasta que el gestor asigne los recursos
                latchEspera.await();
            }
            
            // 3. Recursos asignados, iniciar atención
            gestor.logYEnviar("[ATENCION] Paciente " + id + " (" + nivel + ") está siendo atendido por " + (tiempoAtencionMs/1000) + "s.");
            
            // Simular el tiempo de atención usando la variable de clase calculada
            Thread.sleep(tiempoAtencionMs);
            
            // 4. Finalizar atención y liberar recursos
            gestor.logYEnviar("[SALIDA] Paciente " + id + " (" + nivel + ") terminó su atención. Liberando recursos...");
            gestor.liberarRecursos(this);
            
        } catch (InterruptedException e) {
            System.err.println("El hilo del paciente " + id + " fue interrumpido.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Compara pacientes para ordenarlos en la PriorityBlockingQueue.
     * Pacientes con prioridad más baja (ej. 1) deben ir antes.
     */
    @Override
    public int compareTo(Paciente otro) {
        // Orden ascendente por prioridad (1 es antes que 5)
        int comparacionPrioridad = Integer.compare(this.nivel.getPrioridad(), otro.nivel.getPrioridad());
        
        // Si tienen la misma prioridad, se atiende por orden de llegada (id)
        if (comparacionPrioridad == 0) {
            return Integer.compare(this.id, otro.id);
        }
        return comparacionPrioridad;
    }

    @Override
    public String toString() {
        return "Paciente " + id + " [" + nivel + "]";
    }
}
