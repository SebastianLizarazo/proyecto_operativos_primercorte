import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TrafficSimulation.java
 * ----------------------
 * Simulación concurrente de un cruce de calles usando Semaphores y Threads.
 *
 * Características:
 * - Múltiples carriles por dirección (NORTE-SUR / ESTE-OESTE).
 * - Cada carril tiene un "semáforo verde" modelado con un Semaphore que el
 *   controlador libera por ventana (permite que varios vehículos crucen durante
 *   el intervalo verde).
 * - Un semáforo de conteo (intersectionSemaphore) controla cuántos vehículos
 *   pueden estar simultáneamente dentro de la sección crítica (el cruce).
 * - Ciclo de semáforo: verde -> amarillo -> rojo.
 * - Métricas simples: conteo de vehículos que cruzaron por carril.
 *
 * Cómo funciona (resumen):
 * - Los vehículos son hilos que primero esperan a que su carril reciba permisos
 *   (green.release(n)) para pasar (ventana verde). Cuando obtienen un permiso,
 *   intentan adquirir un permit en intersectionSemaphore para realmente entrar al
 *   cruce. Al terminar, liberan el permit del cruce.
 * - El TrafficController libera un número limitado de permisos por carril en
 *   cada ventana verde (permitsPerGreen). Cuando cambia a rojo, descarta los
 *   permisos sobrantes usando drainPermits().
 *
 * Extensiones sugeridas (ej.: para la entrega):
 * - Añadir más direcciones (giro a la izquierda, peatones).
 * - Parámetros configurables por archivo JSON/CLI (duraciones, tamaños).
 * - Registro a fichero / CSV para análisis de throughput y latencias.
 * - Tests unitarios que simulen picos de tráfico.
 */
public class TrafficSimulation {

    // Configuración de la simulación (ajustables)
    static final int LANES_PER_DIRECTION = 2; // carriles por cada dirección
    static final int MAX_CONCURRENT_IN_INTERSECTION = 2; // semáforo de conteo

    static final int VEHICLES_TO_SPAWN = 40; // total de vehículos a generar

    // Duraciones en milisegundos
    static final long GREEN_DURATION_MS = 5000; // 5s verde
    static final long YELLOW_DURATION_MS = 2000; // 2s amarillo
    static final long RED_DURATION_MS = 0; // se usa implicitamente al cambiar de dirección

    // During each green window, cuántos permisos (vehículos) se abren por carril
    static final int PERMITS_PER_GREEN = 6;

    // Simulación total (opcional) - la controladora puede parar después de este tiempo
    static final long SIMULATION_TIME_MS = 30_000; // 30s

    // Semáforo que limita el número de vehículos dentro del cruce al mismo tiempo.
    final Semaphore intersectionSemaphore = new Semaphore(MAX_CONCURRENT_IN_INTERSECTION);

    final List<Lane> lanes = new ArrayList<>();
    final TrafficController controller;

    final AtomicBoolean running = new AtomicBoolean(true);

    public TrafficSimulation() {
        // Crear carriles: alternaremos nombres para dos direcciones: NS y EW
        for (int i = 1; i <= LANES_PER_DIRECTION; i++) {
            lanes.add(new Lane("NS-" + i, Direction.NORTH_SOUTH));
        }
        for (int i = 1; i <= LANES_PER_DIRECTION; i++) {
            lanes.add(new Lane("EW-" + i, Direction.EAST_WEST));
        }
        controller = new TrafficController();
    }

    public void start() {
        // Iniciar controller
        new Thread(controller, "TrafficController").start();

        // Generar vehículos "de forma aleatoria" distribuidos entre carriles
        Random rnd = new Random();
        int id = 1;
        for (int i = 0; i < VEHICLES_TO_SPAWN; i++) {
            Lane lane = lanes.get(rnd.nextInt(lanes.size()));
            Vehicle v = new Vehicle(id++, lane);
            new Thread(v, "Veh-" + v.id).start();

            // Simular intervalos de llegada aleatorios (0 - 700 ms)
            try {
                Thread.sleep(rnd.nextInt(700));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Detener la simulación después de SIMULATION_TIME_MS
        try {
            Thread.sleep(SIMULATION_TIME_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running.set(false);
        System.out.println("\n--- Simulación finalizada. Esperando que terminen hilos activos... ---\n");

        // Mostrar métricas
        printMetrics();
    }

    private void printMetrics() {
        System.out.println("Métricas por carril:");
        for (Lane lane : lanes) {
            System.out.printf("%s (dir=%s) -> vehículos cruzados: %d\n", lane.name, lane.direction, lane.passedCount.get());
        }
        System.out.printf("Vehículos totales cruzados: %d\n", lanes.stream().mapToInt(l -> l.passedCount.get()).sum());
    }

    // Direcciones de tráfico (básico)
    enum Direction {
        NORTH_SOUTH, EAST_WEST
    }

    // Representación de un carril
    static class Lane {
        final String name;
        final Direction direction;
        // Semaphore usado como "puerta verde". Inicialmente 0 permisos: bloquea los vehículos.
        final Semaphore greenGate = new Semaphore(0);
        final AtomicInteger passedCount = new AtomicInteger(0);

        Lane(String name, Direction direction) {
            this.name = name;
            this.direction = direction;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Vehículo (hilo)
    class Vehicle implements Runnable {
        final int id;
        final Lane lane;
        final Random rnd = new Random();

        Vehicle(int id, Lane lane) {
            this.id = id;
            this.lane = lane;
        }

        @Override
        public void run() {
            try {
                System.out.printf("%s llega al carril %s\n", threadName(), lane.name);

                // Espera hasta que su carril reciba permisos (ventana verde)
                lane.greenGate.acquire(); // bloquea hasta que el controlador libere permisos

                // Una vez tiene permiso de paso, intenta entrar al cruce (sección crítica)
                boolean entered = false;
                try {
                    intersectionSemaphore.acquire();
                    entered = true;
                    System.out.printf("%s -> ENTRANDO al cruce desde %s\n", threadName(), lane.name);

                    // Simular tiempo de cruce (500 - 1500 ms)
                    Thread.sleep(500 + rnd.nextInt(1000));

                    lane.passedCount.incrementAndGet();
                    System.out.printf("%s -> SALIENDO del cruce desde %s\n", threadName(), lane.name);
                } finally {
                    if (entered) {
                        intersectionSemaphore.release();
                    }
                }

            } catch (InterruptedException e) {
                System.out.printf("%s fue interrumpido\n", threadName());
                Thread.currentThread().interrupt();
            }
        }

        private String threadName() {
            return "Veh-" + id;
        }
    }

    // Controlador de semáforos: alterna ventanas verdes entre las dos direcciones.
    class TrafficController implements Runnable {

        @Override
        public void run() {
            long startTime = System.currentTimeMillis();
            while (running.get()) {
                // Ventana verde para NORTH_SOUTH
                setGreenFor(Direction.NORTH_SOUTH);
                sleepWithInterrupt(GREEN_DURATION_MS);

                // Amarillo: no liberar nuevos permisos, esperar un poco
                System.out.println("--- AMARILLO para NORTH_SOUTH (ventana cerrándose) ---");
                sleepWithInterrupt(YELLOW_DURATION_MS);

                // Al cerrar verde, drenar permisos que quedaron sin usar para evitar "carry-over"
                drainGreenFor(Direction.NORTH_SOUTH);

                if (!running.get()) break;

                // Ventana verde para EAST_WEST
                setGreenFor(Direction.EAST_WEST);
                sleepWithInterrupt(GREEN_DURATION_MS);

                System.out.println("--- AMARILLO para EAST_WEST (ventana cerrándose) ---");
                sleepWithInterrupt(YELLOW_DURATION_MS);
                drainGreenFor(Direction.EAST_WEST);

                // opcional: comprobar tiempo total
                if (System.currentTimeMillis() - startTime > SIMULATION_TIME_MS && !running.get()) {
                    break;
                }
            }
            System.out.println("TrafficController -> detenido.");
        }

        private void setGreenFor(Direction dir) {
            System.out.printf("--- VERDE para %s ---\n", dir);
            for (Lane lane : lanes) {
                if (lane.direction == dir) {
                    // Liberar un número limitado de permisos para la ventana verde
                    lane.greenGate.release(PERMITS_PER_GREEN);
                    System.out.printf("(controller) Abiertos %d permisos para %s\n", PERMITS_PER_GREEN, lane.name);
                }
            }
        }

        private void drainGreenFor(Direction dir) {
            for (Lane lane : lanes) {
                if (lane.direction == dir) {
                    int drained = lane.greenGate.drainPermits();
                    if (drained > 0) {
                        System.out.printf("(controller) Drenados %d permisos sobrantes en %s\n", drained, lane.name);
                    }
                }
            }
        }

        private void sleepWithInterrupt(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Punto de entrada
    public static void main(String[] args) {
        System.out.println("=== Simulación de semáforos de tráfico (Java) ===\n");
        TrafficSimulation sim = new TrafficSimulation();
        sim.start();
    }
}
