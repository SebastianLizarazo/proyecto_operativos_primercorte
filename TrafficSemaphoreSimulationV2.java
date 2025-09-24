import javax.swing.*;
import java.awt.*;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Semaphore;

/**
 * Simulación de tráfico con semáforos utilizando conceptos de sistemas
 * operativos.
 * 
 * Esta aplicación simula un cruce de calles con:
 * - Control de semáforos automático con cambio de luces
 * - Gestión de concurrencia mediante semáforos de Java
 * - Máximo de 3 vehículos cruzando simultáneamente
 * - Interfaz gráfica en tiempo real con Swing
 * - Generación automática de vehículos con límite configurable
 * - Terminación automática cuando todos los vehículos han pasado
 * 
 * Conceptos de SO implementados:
 * - Exclusión mutua con semáforos
 * - Sincronización de hilos
 * - Monitores (synchronized blocks)
 * - Variables condition (wait/notify)
 * 
 * @author Sistema de Simulación de Tráfico
 * @version 2.0
 * @since 2025
 */
public class TrafficSemaphoreSimulationV2 {

    /**
     * Enumeración que define las direcciones de tráfico en el cruce.
     * NorteSur: Tráfico que va de norte a sur (vertical)
     * EsteOeste: Tráfico que va de este a oeste (horizontal)
     */
    enum Direction {
        /** Dirección vertical del tráfico */
        NorteSur,
        /** Dirección horizontal del tráfico */
        EsteOeste
    }

    // ==================== SEMÁFOROS Y CONTROL DE CONCURRENCIA ====================

    /**
     * Semáforo contador que limita a máximo 3 vehículos cruzando simultáneamente.
     * Implementa el concepto de exclusión mutua limitada para evitar congestión.
     */
    private final Semaphore crossingSemaphore = new Semaphore(3);

    /**
     * Semáforo binario para proteger el acceso concurrente a la GUI.
     * Evita condiciones de carrera al actualizar la interfaz gráfica.
     */
    private final Semaphore guiLock = new Semaphore(1);

    /**
     * Monitor (objeto de sincronización) para coordinar cambios de semáforo.
     * Utilizado con wait/notify para despertar vehículos cuando cambia la luz.
     */
    private final Object lightMonitor = new Object();

    // ==================== ESTADO DEL SISTEMA ====================

    /**
     * Dirección actual que tiene luz verde (acceso volátil para visibilidad entre
     * hilos)
     */
    private volatile Direction currentGreen = Direction.NorteSur;

    /** Lista thread-safe de todos los vehículos en el sistema */
    private final List<Vehicle> vehicles = new ArrayList<>();

    /** Referencia al panel gráfico para renderizado */
    private TrafficPanel panel;

    // ==================== CONFIGURACIÓN TEMPORAL ====================

    /** Duración de la luz verde en milisegundos */
    private final int GREEN_MS = 4000; // 4 segundos en verde

    /** Duración de la luz amarilla en milisegundos */
    private final int YELLOW_MS = 2000; // 2 segundos en amarillo

    /** Intervalo entre generación de vehículos en milisegundos */
    private final int SPAWN_MS = 1000; // 1 segundo entre vehículos

    /** Límite máximo de vehículos a generar en la simulación */
    private final int MAX_VEHICLES = 20;

    // ==================== CONTADORES Y FLAGS ====================

    /** Contador incremental para identificar vehículos únicos */
    private int vehicleCounter = 0;

    /** Contador thread-safe de vehículos activos en el sistema */
    private volatile int activeVehicles = 0;

    /** Bandera para coordinar el cierre ordenado de la simulación */
    private volatile boolean shouldStop = false;

    /**
     * Punto de entrada principal de la aplicación.
     * Utiliza SwingUtilities.invokeLater para garantizar que la GUI
     * se ejecute en el Event Dispatch Thread (EDT).
     * 
     * @param args Argumentos de línea de comandos (no utilizados)
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TrafficSemaphoreSimulationV2().start());
    }

    /**
     * Inicializa y comienza la simulación de tráfico.
     * 
     * Secuencia de inicialización:
     * 1. Crea e inicializa la interfaz gráfica
     * 2. Inicia el hilo controlador de semáforos
     * 3. Inicia el hilo generador de vehículos
     * 
     * El generador crea vehículos hasta alcanzar MAX_VEHICLES,
     * cada uno con dirección aleatoria y timing variable.
     */
    private void start() {
        // Inicializar la interfaz gráfica
        createAndShowGUI();

        // Iniciar el controlador de semáforos en un hilo separado
        new Thread(new TrafficLightController()).start();

        // Iniciar el generador de vehículos en un hilo separado
        new Thread(() -> {
            Random r = new Random();
            // Generar vehículos hasta alcanzar el límite máximo
            while (vehicleCounter < MAX_VEHICLES) {
                try {
                    // Espera variable entre vehículos (1-1.8 segundos)
                    Thread.sleep((long) (SPAWN_MS + r.nextInt(800)));
                } catch (InterruptedException e) {
                    break;
                }

                // Verificación adicional del límite por seguridad
                if (vehicleCounter >= MAX_VEHICLES) {
                    log("Límite de vehículos alcanzado: " + MAX_VEHICLES);
                    break;
                }

                // Crear vehículo con dirección aleatoria
                Direction dir = r.nextBoolean() ? Direction.NorteSur : Direction.EsteOeste;
                Vehicle v = new Vehicle("V" + (++vehicleCounter), dir);
                addVehicleToModel(v);
                // Ejecutar cada vehículo en su propio hilo
                new Thread(v).start();
            }
            log("Generación de vehículos completada. Total: " + vehicleCounter);
        }, "Spawner").start();
    }

    /**
     * Añade un vehículo al modelo de datos de forma thread-safe.
     * Utiliza el semáforo guiLock para evitar condiciones de carrera
     * al modificar la lista de vehículos y el contador de activos.
     * 
     * @param v El vehículo a añadir al sistema
     */
    private void addVehicleToModel(Vehicle v) {
        try {
            guiLock.acquire(); // Adquirir exclusión mutua
            vehicles.add(v);
            activeVehicles++; // Incrementar contador de vehículos activos
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            guiLock.release(); // Siempre liberar el semáforo
        }
    }

    /**
     * Elimina un vehículo del modelo de datos de forma thread-safe.
     * Además de eliminar el vehículo, verifica si la simulación debe terminar
     * (cuando no quedan vehículos activos y ya se generaron todos).
     * 
     * @param v El vehículo a eliminar del sistema
     */
    private void removeVehicleFromModel(Vehicle v) {
        try {
            guiLock.acquire(); // Adquirir exclusión mutua
            vehicles.remove(v);
            activeVehicles--; // Decrementar contador de vehículos activos

            // Verificar condición de terminación de la simulación
            if (activeVehicles <= 0 && vehicleCounter >= MAX_VEHICLES) {
                shouldStop = true;
                log("Todos los vehículos han terminado. Deteniendo semáforos...");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            guiLock.release(); // Siempre liberar el semáforo
        }
    }

    /**
     * Crea e inicializa la interfaz gráfica de usuario.
     * 
     * Configuración de la ventana:
     * - Título descriptivo de la aplicación
     * - Tamaño 700x700 píxeles para visualización adecuada
     * - Centrada en la pantalla
     * - Timer para actualización automática a 25 FPS (40ms)
     */
    private void createAndShowGUI() {
        JFrame frame = new JFrame("Simulación Semáforo - Intersection (Simple) V2");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        panel = new TrafficPanel();
        frame.add(panel);
        frame.setSize(700, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Timer para refresco automático de la GUI (25 FPS)
        new javax.swing.Timer(40, e -> panel.repaint()).start();
    }

    /**
     * Controlador automático de semáforos que implementa el ciclo de luces.
     * 
     * Esta clase implementa Runnable para ejecutarse en un hilo separado
     * y gestiona el cambio automático de luces del semáforo:
     * 
     * Ciclo de funcionamiento:
     * 1. Luz verde por GREEN_MS milisegundos (4 segundos)
     * 2. Luz amarilla por YELLOW_MS milisegundos (2 segundos)
     * 3. Cambio de dirección y vuelta al paso 1
     * 
     * Utiliza el patrón de monitor con wait/notify para despertar
     * a los vehículos cuando cambia la luz verde.
     */
    private class TrafficLightController implements Runnable {
        /**
         * Bucle principal del controlador de semáforos.
         * Ejecuta el ciclo de luces hasta que se active la bandera shouldStop.
         * 
         * Para cada dirección:
         * 1. Activa luz verde y notifica a vehículos esperando
         * 2. Espera el tiempo configurado para luz verde
         * 3. Activa luz amarilla (periodo de precaución)
         * 4. Cambia a la dirección opuesta
         */
        @Override
        public void run() {
            while (!shouldStop) {
                // Fase verde: permitir paso de vehículos
                log("Semáforo: " + currentGreen + " -> VERDE");
                notifyLightChange();
                if (!sleepWithCheck(GREEN_MS))
                    break;

                // Fase amarilla: advertencia de cambio inminente
                log("Semáforo: " + currentGreen + " -> AMARILLO");
                if (!sleepWithCheck(YELLOW_MS))
                    break;

                // Cambio de dirección: alternar entre NorteSur y EsteOeste
                currentGreen = (currentGreen == Direction.NorteSur) ? Direction.EsteOeste : Direction.NorteSur;
                log("Semáforo: Cambiando a " + currentGreen + " (ahora VERDE)");
                notifyLightChange();
            }
            log("Controlador de semáforos detenido.");
        }

        /**
         * Notifica a todos los vehículos esperando sobre el cambio de luz.
         * Utiliza el patrón monitor: sincronizado + notifyAll para despertar
         * a todos los hilos que están esperando en lightMonitor.wait().
         * También actualiza la interfaz gráfica con el nuevo estado.
         */
        private void notifyLightChange() {
            synchronized (lightMonitor) {
                lightMonitor.notifyAll(); // Despertar a todos los vehículos esperando
            }
            panel.setCurrentGreen(currentGreen); // Actualizar visualización
        }

        /**
         * Duerme por el tiempo especificado mientras verifica periódicamente
         * si la simulación debe detenerse.
         * 
         * @param ms Tiempo a dormir en milisegundos
         * @return true si completó el tiempo sin interrupción, false si debe parar
         */
        private boolean sleepWithCheck(int ms) {
            try {
                int sleepInterval = 100; // Verificar cada 100ms si debe parar
                int elapsed = 0;
                while (elapsed < ms && !shouldStop) {
                    Thread.sleep(Math.min(sleepInterval, ms - elapsed));
                    elapsed += sleepInterval;
                }
                return !shouldStop;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Representa un vehículo individual en la simulación.
     * 
     * Cada vehículo es un hilo independiente que:
     * 1. Se aproxima al cruce siguiendo su trayectoria
     * 2. Espera a que el semáforo esté en verde para su dirección
     * 3. Adquiere un permiso del semáforo de cruce (máximo 3 simultáneos)
     * 4. Cruza la intersección y libera el permiso
     * 5. Sale del sistema y se auto-elimina
     * 
     * Estados del vehículo:
     * - "llegando": Aproximándose al cruce
     * - "esperando": En el cruce esperando luz verde
     * - "cruzando": Cruzando la intersección (tiene permiso)
     * - "salido": Ha completado su trayecto
     */
    private class Vehicle implements Runnable {
        /** Identificador único del vehículo (V1, V2, etc.) */
        final String id;
        /** Dirección de movimiento del vehículo */
        final Direction dir;
        /** Posición actual en coordenadas X e Y */
        double x, y;
        /** Estado actual del vehículo (volátil para visibilidad entre hilos) */
        volatile String state = "llegando";

        // Puntos clave en la trayectoria del vehículo
        /** Punto de inicio del recorrido */
        private final Point start;
        /** Punto donde debe detenerse antes del cruce */
        private final Point stop;
        /** Punto de salida del sistema */
        private final Point exit;
        /** Velocidad de movimiento (píxeles por frame) */
        private final double step = 3.0;

        /**
         * Constructor del vehículo que define la trayectoria según la dirección.
         * 
         * Para tráfico NorteSur (vertical):
         * - Inicia desde arriba (y=-10), se detiene antes del cruce (y=260)
         * - Sale por abajo (y=720)
         * 
         * Para tráfico EsteOeste (horizontal):
         * - Inicia desde la izquierda (x=-10), se detiene antes del cruce (x=260)
         * - Sale por la derecha (x=720)
         * 
         * @param id  Identificador único del vehículo
         * @param dir Dirección de movimiento
         */
        Vehicle(String id, Direction dir) {
            this.id = id;
            this.dir = dir;
            // Configurar puntos de trayectoria según la dirección
            if (dir == Direction.NorteSur) {
                // Tráfico vertical: de norte a sur
                start = new Point(340, -10); // Arriba, fuera de pantalla
                stop = new Point(340, 260); // Antes del cruce
                exit = new Point(340, 720); // Abajo, fuera de pantalla
            } else {
                // Tráfico horizontal: de este a oeste
                start = new Point(-10, 340); // Izquierda, fuera de pantalla
                stop = new Point(260, 340); // Antes del cruce
                exit = new Point(720, 340); // Derecha, fuera de pantalla
            }
            this.x = start.x;
            this.y = start.y;
        }

        /**
         * Lógica principal del comportamiento del vehículo.
         * 
         * Implementa el protocolo completo de paso por el cruce:
         * 1. Retardo inicial aleatorio (simula tiempo de aparición)
         * 2. Aproximación al punto de parada
         * 3. Espera por luz verde usando el patrón monitor
         * 4. Adquisición de permiso de cruce (semáforo contador)
         * 5. Cruce de la intersección
         * 6. Liberación de permiso y salida del sistema
         */
        @Override
        public void run() {
            try {
                // Retardo inicial aleatorio (200ms - 1.4s)
                Thread.sleep((long) (200 + Math.random() * 1200));
            } catch (InterruptedException e) {
                return;
            }

            // FASE 1: Aproximación al cruce
            log(id + " (" + dir + ") está aproximándose.");
            moveTo(stop);
            state = "esperando";
            log(id + " quiere cruzar - esperando luz " + dir);

            // FASE 2: Espera por luz verde (patrón monitor)
            synchronized (lightMonitor) {
                while (currentGreen != dir) {
                    try {
                        lightMonitor.wait(); // Bloquear hasta notify del semáforo
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            // FASE 3: Adquisición de permiso de cruce
            try {
                log(id + " trata de adquirir permiso para cruzar...");
                crossingSemaphore.acquire(); // Bloquear si ya hay 3 cruzando
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // FASE 4: Cruce de la intersección
            state = "cruzando";
            log(">>> " + id + " está cruzando (permiso adquirido).");
            moveTo(exit);
            crossingSemaphore.release(); // Liberar permiso para otros vehículos
            state = "salido";
            log("<<< " + id + " ha salido del cruce.");

            // FASE 5: Limpieza y salida del sistema
            try {
                Thread.sleep(500); // Pequeña pausa antes de auto-eliminarse
            } catch (InterruptedException ignored) {
            }
            removeVehicleFromModel(this); // Auto-eliminación del sistema
        }

        /**
         * Mueve el vehículo gradualmente hacia el punto objetivo.
         * 
         * Utiliza interpolación lineal para crear movimiento suave:
         * 1. Calcula vector de dirección hacia el objetivo
         * 2. Normaliza la velocidad según el step configurado
         * 3. Mueve en pequeños incrementos hasta llegar
         * 4. Actualiza la GUI y introduce variabilidad temporal
         * 
         * @param target Punto de destino hacia el cual moverse
         */
        private void moveTo(Point target) {
            double dx = target.x - x;
            double dy = target.y - y;
            double dist = Math.hypot(dx, dy);
            if (dist == 0)
                return;

            // Calcular vector de velocidad normalizado
            double vx = (dx / dist) * step;
            double vy = (dy / dist) * step;

            // Movimiento gradual hasta el objetivo
            while (Math.hypot(target.x - x, target.y - y) > step) {
                x += vx;
                y += vy;
                try {
                    // Actualizar GUI y simular variabilidad de velocidad
                    SwingUtilities.invokeLater(() -> panel.repaint());
                    Thread.sleep(25 + (int) (Math.random() * 40)); // 25-65ms por frame
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Posicionamiento exacto en el objetivo
            x = target.x;
            y = target.y;
        }

        // ==================== MÉTODOS DE ACCESO (GETTERS) ====================

        /** @return Identificador del vehículo */
        String getId() {
            return id;
        }

        /** @return Dirección de movimiento del vehículo */
        Direction getDir() {
            return dir;
        }

        /** @return Coordenada X actual */
        double getX() {
            return x;
        }

        /** @return Coordenada Y actual */
        double getY() {
            return y;
        }

        /** @return Estado actual del vehículo */
        String getState() {
            return state;
        }
    }

    /**
     * Panel personalizado de Swing para la visualización gráfica de la simulación.
     * 
     * Responsabilidades:
     * - Renderizar el cruce de calles y las carreteras
     * - Dibujar semáforos con estados actuales (rojo/verde)
     * - Mostrar vehículos con colores según su estado
     * - Visualizar información del sistema en tiempo real
     * 
     * Codificación de colores de vehículos:
     * - Blanco: Llegando al cruce
     * - Naranja: Esperando luz verde
     * - Verde: Cruzando la intersección
     * - Gris claro: Ha salido del sistema
     */
    private class TrafficPanel extends JPanel {
        /** Copia local de la dirección con luz verde (para evitar inconsistencias) */
        private Direction currentGreenLocal = currentGreen;

        /**
         * Actualiza la dirección que tiene luz verde.
         * 
         * @param d Nueva dirección con luz verde
         */
        void setCurrentGreen(Direction d) {
            this.currentGreenLocal = d;
            repaint(); // Forzar actualización visual
        }

        /**
         * Método principal de renderizado del panel.
         * 
         * Secuencia de dibujado:
         * 1. Fondo gris oscuro (representa el área no pavimentada)
         * 2. Carreteras gris claro (vertical y horizontal)
         * 3. Intersección gris medio (área de cruce)
         * 4. Líneas divisorias blancas (marcas viales)
         * 5. Semáforos con estado actual
         * 6. Vehículos con colores según estado
         * 7. Información del sistema (texto overlay)
         * 
         * @param g Contexto gráfico para el renderizado
         */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            // === FONDO Y ESTRUCTURA BÁSICA ===
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());

            // Carreteras principales (vertical y horizontal)
            g.setColor(Color.GRAY);
            g.fillRect(300, 0, 80, getHeight()); // Carretera vertical
            g.fillRect(0, 300, getWidth(), 80); // Carretera horizontal

            // Área de intersección
            g.setColor(new Color(70, 70, 70));
            g.fillRect(300, 300, 80, 80);

            // === MARCAS VIALES ===
            g.setColor(Color.WHITE);
            // Líneas divisorias verticales (6 segmentos)
            for (int i = 0; i < 6; i++) {
                g.fillRect(320, 240 + i * 8, 10, 4);
            }
            // Líneas divisorias horizontales (6 segmentos)
            for (int i = 0; i < 6; i++) {
                g.fillRect(240 + i * 8, 320, 4, 10);
            }

            // === SEMÁFOROS ===
            drawSemaphore(g, 360, 200, "NorteSur"); // Semáforo para tráfico vertical
            drawSemaphore(g, 180, 360, "EW"); // Semáforo para tráfico horizontal

            // === VEHÍCULOS ===
            try {
                guiLock.acquire(); // Acceso exclusivo a la lista de vehículos
                for (Vehicle v : vehicles) {
                    // Seleccionar color según el estado del vehículo
                    switch (v.getState()) {
                        case "llegando":
                            g.setColor(Color.WHITE); // Aproximándose
                            break;
                        case "esperando":
                            g.setColor(Color.ORANGE); // Esperando luz verde
                            break;
                        case "cruzando":
                            g.setColor(Color.GREEN); // Cruzando intersección
                            break;
                        case "salido":
                            g.setColor(Color.LIGHT_GRAY); // Ha completado el recorrido
                            break;
                        default:
                            g.setColor(Color.CYAN); // Estado desconocido
                            break;
                    }
                    // Dibujar vehículo como círculo con ID
                    int r = 8; // Radio del círculo
                    g.fillOval((int) v.getX() - r, (int) v.getY() - r, r * 2, r * 2);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Arial", Font.PLAIN, 10));
                    g.drawString(v.getId(), (int) v.getX() - 6, (int) v.getY() - 10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                guiLock.release(); // Siempre liberar el semáforo
            }

            // === INFORMACIÓN DEL SISTEMA ===
            g.setColor(Color.WHITE);
            g.drawString(
                    "Green: " + currentGreenLocal + "    Permits crossing: " + crossingSemaphore.availablePermits(),
                    10, 15);
            g.drawString("Vehículos generados: " + vehicleCounter + "/" + MAX_VEHICLES, 10, 30);
            g.drawString("Vehículos activos: " + activeVehicles, 10, 45);
            if (shouldStop) {
                g.setColor(Color.RED);
                g.drawString("SIMULACIÓN COMPLETADA", 10, 60);
            }
        }

        /**
         * Dibuja un semáforo individual con su estado actual.
         * 
         * Estructura del semáforo:
         * - Rectángulo negro como base (30x70 píxeles)
         * - Tres círculos: rojo (arriba), amarillo (medio), verde (abajo)
         * - Solo se ilumina la luz correspondiente al estado actual
         * 
         * Lógica de estado:
         * - Luz roja: Cuando la dirección opuesta tiene verde
         * - Luz amarilla: Siempre apagada (se maneja en el timing)
         * - Luz verde: Cuando esta dirección tiene paso
         * 
         * @param g     Contexto gráfico
         * @param sx    Coordenada X del semáforo
         * @param sy    Coordenada Y del semáforo
         * @param label Etiqueta identificadora ("NorteSur" o "EW")
         */
        private void drawSemaphore(Graphics g, int sx, int sy, String label) {
            // Base del semáforo
            g.setColor(Color.BLACK);
            g.fillRect(sx, sy, 30, 70);

            // === LUZ ROJA (superior) ===
            g.setColor(currentGreenLocal == null ? Color.DARK_GRAY
                    : (currentGreenLocal == (label.equals("NorteSur") ? Direction.EsteOeste : Direction.NorteSur)
                            ? Color.RED
                            : Color.DARK_GRAY));
            g.fillOval(sx + 5, sy + 5, 20, 20);

            // === LUZ AMARILLA (media) ===
            g.setColor(Color.DARK_GRAY); // Siempre apagada en esta implementación
            g.fillOval(sx + 5, sy + 28, 20, 20);

            // === LUZ VERDE (inferior) ===
            g.setColor(currentGreenLocal == (label.equals("NorteSur") ? Direction.NorteSur : Direction.EsteOeste)
                    ? Color.GREEN
                    : Color.DARK_GRAY);
            g.fillOval(sx + 5, sy + 50, 20, 20);

            // Etiqueta identificadora del semáforo
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.PLAIN, 10));
            g.drawString(label, sx + 4, sy + 68);
        }
    }

    /**
     * Método de utilidad para registrar eventos del sistema.
     * Incluye timestamp para rastrear la secuencia temporal de eventos.
     * 
     * Útil para debugging y análisis del comportamiento del sistema:
     * - Cambios de estado de vehículos
     * - Transiciones de semáforos
     * - Adquisición/liberación de permisos de cruce
     * - Eventos de inicio/fin de simulación
     * 
     * @param s Mensaje a registrar en el log
     */
    private void log(String s) {
        System.out.printf("[%tT] %s%n", new Date(), s);
    }
}
