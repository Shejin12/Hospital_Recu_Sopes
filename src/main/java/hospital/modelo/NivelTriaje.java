package hospital.modelo;

/**
 * Representa los 5 niveles de triaje en el departamento de emergencias.
 * Define la prioridad (1 es la más alta) y los recursos exactos requeridos por cada nivel.
 */
public enum NivelTriaje {
    
    NIVEL_1_CRITICO(1, 0, 1, 0, 1, 2, 1, 1),
    NIVEL_2_EMERGENCIA(2, 1, 0, 1, 0, 1, 0, 1),
    NIVEL_3_URGENTE(3, 1, 0, 1, 0, 0, 0, 0),
    NIVEL_4_MENOS_URGENTE(4, 1, 0, 1, 0, 0, 0, 0),
    NIVEL_5_NO_URGENTE(5, 1, 0, 1, 0, 0, 0, 0);

    private final int prioridad;
    
    // Cantidades requeridas de cada recurso
    private final int salasRequeridas;
    private final int quirofanosRequeridos;
    private final int medicosRequeridos;
    private final int cirujanosRequeridos;
    private final int enfermerasRequeridas;
    private final int ventiladoresRequeridos;
    private final int monitoresRequeridos;

    NivelTriaje(int prioridad, int salas, int quirofanos, int medicos, int cirujanos, int enfermeras, int ventiladores, int monitores) {
        this.prioridad = prioridad;
        this.salasRequeridas = salas;
        this.quirofanosRequeridos = quirofanos;
        this.medicosRequeridos = medicos;
        this.cirujanosRequeridos = cirujanos;
        this.enfermerasRequeridas = enfermeras;
        this.ventiladoresRequeridos = ventiladores;
        this.monitoresRequeridos = monitores;
    }

    public int getPrioridad() { return prioridad; }
    public int getSalasRequeridas() { return salasRequeridas; }
    public int getQuirofanosRequeridos() { return quirofanosRequeridos; }
    public int getMedicosRequeridos() { return medicosRequeridos; }
    public int getCirujanosRequeridos() { return cirujanosRequeridos; }
    public int getEnfermerasRequeridas() { return enfermerasRequeridas; }
    public int getVentiladoresRequeridos() { return ventiladoresRequeridos; }
    public int getMonitoresRequeridos() { return monitoresRequeridos; }
    
    /**
     * Genera un nivel de triaje aleatorio del 1 al 5.
     */
    public static NivelTriaje generarAleatorio() {
        int rand = (int) (Math.random() * 5) + 1;
        switch (rand) {
            case 1: return NIVEL_1_CRITICO;
            case 2: return NIVEL_2_EMERGENCIA;
            case 3: return NIVEL_3_URGENTE;
            case 4: return NIVEL_4_MENOS_URGENTE;
            case 5: return NIVEL_5_NO_URGENTE;
            default: return NIVEL_5_NO_URGENTE;
        }
    }
}
