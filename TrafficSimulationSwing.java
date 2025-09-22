import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TrafficSimulationSwing.java
 * ---------------------------
 * Versión mejorada de la GUI para visualizar de forma más clara la simulación:
 * - Los vehículos se muestran como puntos y avanzan hacia la línea de
 * detención.
 * - Cuando llegan a la línea de detención se forman colas visibles por carril.
 * - Durante rojo, los vehículos permanecen en la fila; cuando su carril se pone
 * verde, los vehículos obtienen permiso y intentan entrar a la intersección.
 * - Se muestran semáforos claramente cerca de cada carril y contadores por
 * carril.
 *
 * Mejora principal sobre la versión anterior: los hilos de vehículo ya no
 * quedan
 * bloqueados invisiblemente antes de posicionarse; primero llegan y hacen fila
 * (visibles), luego esperan permisos usando tryAcquire con tiempo para seguir
 * siendo gráficos y reactivos.
 *
 * Uso: compilar y ejecutar:
 * javac TrafficSimulationSwing.java
 * java TrafficSimulationSwing
 */
public class TrafficSimulationSwing {

    // Configuración
    static final int LANES_PER_DIRECTION = 2; // por dirección
    static final int MAX_CONCURRENT_IN_INTERSECTION = 2;
    static final int VEHICLES_TO_SPAWN = 10;

    // Duraciones (ms)
    static final long GREEN_DURATION_MS = 4000;
    static final long YELLOW_DURATION_MS = 1200;
    static final long SIMULATION_TIME_MS = 30000;

    static final int PERMITS_PER_GREEN = 4;

    // Semáforo de conteo para la sección crítica (el cruce)
    final Semaphore intersectionSemaphore = new Semaphore(MAX_CONCURRENT_IN_INTERSECTION);

    // Lista de carriles (2 direcciones: NORTH_SOUTH y EAST_WEST)
    final List<Lane> lanes = new CopyOnWriteArrayList<>();
    final TrafficController controller = new TrafficController();

    final AtomicBoolean running = new AtomicBoolean(true);

    final List<Vehicle> vehicles = new CopyOnWriteArrayList<>();
    final AtomicInteger vehicleIdGenerator = new AtomicInteger(1);

    final Random rnd = new Random();

    final TrafficPanel panel;
    JFrame frame;

    public TrafficSimulationSwing() {
        // Crear carriles
        for (int i = 1; i <= LANES_PER_DIRECTION; i++)
            lanes.add(new Lane("NS-" + i, Direction.NORTH_SOUTH));
        for (int i = 1; i <= LANES_PER_DIRECTION; i++)
            lanes.add(new Lane("EW-" + i, Direction.EAST_WEST));

        panel = new TrafficPanel();
    }

    public void startSimulation() {
        // Iniciar controlador
        new Thread(controller, "TrafficController").start();

        // Generar vehículos
        new Thread(() -> {
            for (int i = 0; i < VEHICLES_TO_SPAWN && running.get(); i++) {
                Lane lane = lanes.get(rnd.nextInt(lanes.size()));
                Vehicle v = new Vehicle(vehicleIdGenerator.getAndIncrement(), lane);
                vehicles.add(v);
                new Thread(v, "Veh-" + v.id).start();

                try {
                    Thread.sleep(150 + rnd.nextInt(600));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Spawner").start();

        // Parar después de tiempo de simulación
        new Thread(() -> {
            try {
                Thread.sleep(SIMULATION_TIME_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.set(false);
            System.out.println("Simulación solicitada a detenerse.");
        }, "TimerStopper").start();

        // Repaint periódico para la GUI
        new Thread(() -> {
            while (running.get()) {
                panel.repaint();
                try {
                    Thread.sleep(33); // ~30 FPS
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "RepaintLoop").start();
    }

    // Enumeración de direcciones
    enum Direction {
        NORTH_SOUTH, EAST_WEST
    }

    // Lane
    static class Lane {
        final String name;
        final Direction direction;
        final Semaphore greenGate = new Semaphore(0); // puerta verde
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

    // Estados del vehículo
    enum VehicleState {
        APPROACHING, WAITING_IN_QUEUE, TRYING_TO_ENTER, IN_INTERSECTION, EXITED
    }

    // Vehículo como hilo
    class Vehicle implements Runnable {
        final int id;
        final Lane lane;

        volatile double x, y; // posición gráfica
        volatile VehicleState state = VehicleState.APPROACHING;

        final double speed = 2.0 + rnd.nextDouble() * 1.8; // pix per step

        // Path control (simple): determine start and end points according to lane
        // direction
        final Point stopLine = new Point(); // where waits if red
        final Point end = new Point();

        Vehicle(int id, Lane lane) {
            this.id = id;
            this.lane = lane;
        }

        @Override
        public void run() {
            try {
                // Initialize graphical position on EDT
                SwingUtilities.invokeAndWait(() -> {
                    Point start = computeStartPoint(panel.getWidth(), panel.getHeight());
                    x = start.x;
                    y = start.y;
                });

                // First, approach the stop line (visible movement)
                approachStopLine();

                state = VehicleState.WAITING_IN_QUEUE;

                // Queueing: remain visible at the stop line, forming a queue behind others
                while (running.get() && state == VehicleState.WAITING_IN_QUEUE) {
                    // compute queue position (index among waiting vehicles in same lane)
                    int index = computeQueueIndex();
                    positionAtQueueIndex(index);
                    // try to acquire green permit with timeout so thread remains responsive and
                    // visible
                    if (lane.greenGate.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                        state = VehicleState.TRYING_TO_ENTER;
                        // try to enter intersection
                        intersectionSemaphore.acquire();
                        state = VehicleState.IN_INTERSECTION;

                        // mark passed
                        lane.passedCount.incrementAndGet();

                        // Move through intersection
                        moveThroughIntersection();

                        // release intersection permit and exit
                        intersectionSemaphore.release();
                        state = VehicleState.EXITED;
                        break;
                    } else {
                        // still waiting
                        Thread.sleep(50);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        private int computeQueueIndex() {
            int idx = 0;
            for (Vehicle v : vehicles) {
                if (v == this)
                    break; // ensure older vehicles counted first
                if (v.lane == this.lane && v.state == VehicleState.WAITING_IN_QUEUE)
                    idx++;
            }
            return idx;
        }

        private void positionAtQueueIndex(int index) {
            // Position vehicles spaced behind stop line depending on lane/direction
            int gap = 16; // pixels between queued vehicles
            if (lane.direction == Direction.NORTH_SOUTH) {
                // vehicles approach from top to bottom: queue upwards from stopLine
                x = stopLine.x;
                y = stopLine.y - (index * gap) - 10; // -10 to avoid overlap with stop line
            } else {
                // EW: approach left to right, queue to the left
                x = stopLine.x - (index * gap) - 10;
                y = stopLine.y;
            }
        }

        private void approachStopLine() throws InterruptedException {
            // Move from off-screen toward stopLine
            // We'll compute start based on panel size
            Point start = computeStartPoint(panel.getWidth(), panel.getHeight());
            x = start.x;
            y = start.y;

            double dx = stopLine.x - x;
            double dy = stopLine.y - y;
            double dist = Math.hypot(dx, dy);
            int steps = Math.max(6, (int) (dist / speed));
            double stepX = dx / steps;
            double stepY = dy / steps;

            for (int s = 0; s < steps && running.get(); s++) {
                x += stepX;
                y += stepY;
                Thread.sleep(30);
            }
            // snap to stopLine
            x = stopLine.x;
            y = stopLine.y;
        }

        private Point computeStartPoint(int panelW, int panelH) {
            int cx = panelW / 2, cy = panelH / 2;
            int laneOffset = 12 + (Integer.parseInt(lane.name.split("-")[1]) - 1) * 16;
            int approach = Math.min(panelW, panelH) / 2 - 40;
            if (lane.direction == Direction.NORTH_SOUTH) {
                int sx = cx - laneOffset;
                int sy = cy - approach;
                // assign stopLine
                stopLine.x = sx;
                stopLine.y = cy - 30;
                // assign end
                end.x = sx;
                end.y = cy + approach;
                return new Point(sx, sy);
            } else {
                int sx = cx - approach;
                int sy = cy + laneOffset;
                stopLine.x = cx - 30;
                stopLine.y = sy;
                end.x = cx + approach;
                end.y = sy;
                return new Point(sx, sy);
            }
        }

        private void moveThroughIntersection() throws InterruptedException {
            // Move from current position (stopLine) to end
            double dx = end.x - x;
            double dy = end.y - y;
            double dist = Math.hypot(dx, dy);
            int steps = Math.max(8, (int) (dist / 3));
            double stepX = dx / steps;
            double stepY = dy / steps;
            for (int s = 0; s < steps && running.get(); s++) {
                x += stepX;
                y += stepY;
                Thread.sleep(30);
            }
            x = end.x;
            y = end.y;
        }

        Color color() {
            switch (state) {
                case APPROACHING:
                    return new Color(0x2E86C1); // azul claro
                case WAITING_IN_QUEUE:
                    return new Color(0x2874A6); // azul más oscuro
                case TRYING_TO_ENTER:
                    return Color.MAGENTA;
                case IN_INTERSECTION:
                    return new Color(0x239B56); // verde
                case EXITED:
                    return Color.LIGHT_GRAY;
                default:
                    return Color.BLACK;
            }
        }
    }

    // Controlador de tráfico
    class TrafficController implements Runnable {
        volatile Direction currentGreen = Direction.NORTH_SOUTH;
        volatile boolean isYellow = false;

        @Override
        public void run() {
            long startTime = System.currentTimeMillis();
            while (running.get()) {
                setGreenFor(Direction.NORTH_SOUTH);
                sleepInterruptibly(GREEN_DURATION_MS);
                isYellow = true;
                sleepInterruptibly(YELLOW_DURATION_MS);
                isYellow = false;
                drainGreenFor(Direction.NORTH_SOUTH);

                if (!running.get())
                    break;

                setGreenFor(Direction.EAST_WEST);
                sleepInterruptibly(GREEN_DURATION_MS);
                isYellow = true;
                sleepInterruptibly(YELLOW_DURATION_MS);
                isYellow = false;
                drainGreenFor(Direction.EAST_WEST);

                if (System.currentTimeMillis() - startTime > SIMULATION_TIME_MS) {
                    running.set(false);
                }
            }
            System.out.println("Controlador detenido.");
        }

        private void setGreenFor(Direction dir) {
            currentGreen = dir;
            for (Lane l : lanes)
                if (l.direction == dir)
                    l.greenGate.release(PERMITS_PER_GREEN);
        }

        private void drainGreenFor(Direction dir) {
            for (Lane l : lanes)
                if (l.direction == dir)
                    l.greenGate.drainPermits();
        }

        private void sleepInterruptibly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Panel gráfico
    class TrafficPanel extends JPanel {
        TrafficPanel() {
            setPreferredSize(new Dimension(900, 700));
            setBackground(new Color(0xF5F5F5));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int cx = w / 2, cy = h / 2;
            int roadW = 180;

            // Draw roads
            g2.setColor(new Color(0x2F2F2F));
            g2.fillRect(cx - roadW / 2, 0, roadW, h); // vertical
            g2.fillRect(0, cy - roadW / 2, w, roadW); // horizontal

            // center box
            g2.setColor(new Color(0x777777));
            int box = 100;
            g2.fillRect(cx - box / 2, cy - box / 2, box, box);

            // lane separators
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(Color.YELLOW);
            g2.drawLine(cx - roadW / 4, 0, cx - roadW / 4, h);
            g2.drawLine(cx + roadW / 4, 0, cx + roadW / 4, h);
            g2.drawLine(0, cy - roadW / 4, w, cy - roadW / 4);
            g2.drawLine(0, cy + roadW / 4, w, cy + roadW / 4);

            // Stop lines (more explicit)
            g2.setColor(Color.WHITE);
            g2.fillRect(cx - roadW / 2, cy - 48, roadW, 6);
            g2.fillRect(cx - 48, cy - roadW / 2, 6, roadW);

            // Draw traffic lights near each stop line with larger icons
            paintTrafficLight(g2, cx - 120, cy - 160, Direction.NORTH_SOUTH);
            paintTrafficLight(g2, cx + 80, cy + 120, Direction.EAST_WEST);

            // Draw arrows indicating allowed flow
            drawDirectionArrows(g2, cx, cy, roadW);

            // Draw vehicles
            for (Vehicle v : vehicles) {
                int vx = (int) Math.round(v.x);
                int vy = (int) Math.round(v.y);
                // if coordinates not initialized skip
                if (vx == 0 && vy == 0)
                    continue;
                g2.setColor(v.color());
                g2.fillOval(vx - 8, vy - 8, 16, 16);
            }

            // Draw per-lane counters and queue sizes
            int hudX = 12, hudY = 22;
            g2.setColor(Color.BLACK);
            g2.drawString("Semáforo: " + controller.currentGreen + (controller.isYellow ? " (AMARILLO)" : ""), hudX,
                    hudY);
            hudY += 16;
            for (Lane l : lanes) {
                int waiting = countWaitingInLane(l);
                g2.drawString(String.format("%s | Pasados: %d  En fila: %d", l.name, l.passedCount.get(), waiting),
                        hudX, hudY);
                hudY += 14;
            }

            g2.dispose();
        }

        private void drawDirectionArrows(Graphics2D g2, int cx, int cy, int roadW) {
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(Color.WHITE);
            // NS arrows (downwards)
            int x1 = cx - 40, x2 = cx + 40;
            for (int x : new int[] { x1, x2 }) {
                g2.drawLine(x, cy - roadW / 2 + 40, x, cy - 40);
                g2.fillPolygon(new int[] { x - 6, x + 6, x }, new int[] { cy - 40, cy - 40, cy - 26 }, 3);
            }
            // EW arrows (rightwards)
            int y1 = cy - 40, y2 = cy + 40;
            for (int y : new int[] { y1, y2 }) {
                g2.drawLine(cx - roadW / 2 + 40, y, cx + 40, y);
                g2.fillPolygon(new int[] { cx + 40, cx + 40, cx + 56 }, new int[] { y - 6, y + 6, y }, 3);
            }
        }

        private void paintTrafficLight(Graphics2D g2, int x, int y, Direction dir) {
            int size = 36;
            g2.setColor(new Color(0x333333));
            g2.fillRoundRect(x, y, size, size * 3 + 10, 8, 8);

            boolean greenOn = controller.currentGreen == dir && !controller.isYellow;
            boolean yellowOn = controller.currentGreen == dir && controller.isYellow;
            boolean redOn = !greenOn && !yellowOn;

            g2.setColor(redOn ? Color.RED : Color.DARK_GRAY);
            g2.fillOval(x + 6, y + 6, size - 12, size - 12);
            g2.setColor(yellowOn ? Color.YELLOW : Color.DARK_GRAY);
            g2.fillOval(x + 6, y + 6 + size, size - 12, size - 12);
            g2.setColor(greenOn ? Color.GREEN : Color.DARK_GRAY);
            g2.fillOval(x + 6, y + 6 + 2 * size, size - 12, size - 12);
        }
    }

    private int countWaitingInLane(Lane lane) {
        int c = 0;
        for (Vehicle v : vehicles)
            if (v.lane == lane && v.state == VehicleState.WAITING_IN_QUEUE)
                c++;
        return c;
    }

    // Construir interfaz y mostrar
    private void buildAndShowGui() {
        frame = new JFrame("Simulación de Semáforos - Swing (Mejorada)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel main = new JPanel(new BorderLayout());
        main.add(panel, BorderLayout.CENTER);

        JPanel controls = new JPanel();
        JButton startBtn = new JButton("Iniciar Simulación");
        JButton stopBtn = new JButton("Detener");
        controls.add(startBtn);
        controls.add(stopBtn);

        startBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startBtn.setEnabled(false);
                startSimulation();
            }
        });

        stopBtn.addActionListener(e -> {
            running.set(false);
        });

        main.add(controls, BorderLayout.SOUTH);

        frame.getContentPane().add(main);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            TrafficSimulationSwing sim = new TrafficSimulationSwing();
            sim.buildAndShowGui();
        });
    }
}
