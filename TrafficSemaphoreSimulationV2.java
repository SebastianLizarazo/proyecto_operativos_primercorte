import javax.swing.*;
import java.awt.*;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Semaphore;

/**
 * TrafficSemaphoreSimulationV2.java
 * Versión corregida -- nombre de clase coincide con el archivo.
 */
public class TrafficSemaphoreSimulationV2 {

    enum Direction {
        NS, EW
    }

    private final Semaphore crossingSemaphore = new Semaphore(3);// Maximo 3 vehiculos cruzando
    private final Semaphore guiLock = new Semaphore(1); // Exclusión mutua GUI
    private final Object lightMonitor = new Object();
    private volatile Direction currentGreen = Direction.NS;
    private final List<Vehicle> vehicles = new ArrayList<>();
    private TrafficPanel panel;

    private final int GREEN_MS = 4000; // 4 segundos en verde
    private final int YELLOW_MS = 2000; // 2 segundos en amarillo
    private final int SPAWN_MS = 1000; // 1 segundo entre vehículos
    private final int MAX_VEHICLES = 20; // Máximo número de vehículos a generar

    private int vehicleCounter = 0;
    private volatile int activeVehicles = 0; // Contador de vehículos activos
    private volatile boolean shouldStop = false; // Bandera para detener semáforos

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TrafficSemaphoreSimulationV2().start());
    }

    private void start() {
        createAndShowGUI();

        new Thread(new TrafficLightController()).start();

        new Thread(() -> {
            Random r = new Random();
            while (vehicleCounter < MAX_VEHICLES) { // Limitar a MAX_VEHICLES
                try {
                    Thread.sleep((long) (SPAWN_MS + r.nextInt(800)));
                } catch (InterruptedException e) {
                    break;
                }

                // Verificar nuevamente el límite por si acaso
                if (vehicleCounter >= MAX_VEHICLES) {
                    log("Límite de vehículos alcanzado: " + MAX_VEHICLES);
                    break;
                }

                Direction dir = r.nextBoolean() ? Direction.NS : Direction.EW;
                Vehicle v = new Vehicle("V" + (++vehicleCounter), dir);
                addVehicleToModel(v);
                new Thread(v).start();
            }
            log("Generación de vehículos completada. Total: " + vehicleCounter);
        }, "Spawner").start();
    }

    private void addVehicleToModel(Vehicle v) {
        try {
            guiLock.acquire();
            vehicles.add(v);
            activeVehicles++; // Incrementar contador de vehículos activos
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            guiLock.release();
        }
    }

    private void removeVehicleFromModel(Vehicle v) {
        try {
            guiLock.acquire();
            vehicles.remove(v);
            activeVehicles--; // Decrementar contador de vehículos activos

            // Si no quedan vehículos activos y ya se generaron todos, detener semáforos
            if (activeVehicles <= 0 && vehicleCounter >= MAX_VEHICLES) {
                shouldStop = true;
                log("Todos los vehículos han terminado. Deteniendo semáforos...");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            guiLock.release();
        }
    }

    private void createAndShowGUI() {
        JFrame frame = new JFrame("Simulación Semáforo - Intersection (Simple) V2");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        panel = new TrafficPanel();
        frame.add(panel);
        frame.setSize(700, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Usar javax.swing.Timer explícito para evitar ambigüedad
        new javax.swing.Timer(40, e -> panel.repaint()).start();
    }

    private class TrafficLightController implements Runnable {
        @Override
        public void run() {
            while (!shouldStop) {
                log("Semáforo: " + currentGreen + " -> VERDE");
                notifyLightChange();
                if (!sleepWithCheck(GREEN_MS))
                    break;

                log("Semáforo: " + currentGreen + " -> AMARILLO");
                if (!sleepWithCheck(YELLOW_MS))
                    break;

                currentGreen = (currentGreen == Direction.NS) ? Direction.EW : Direction.NS;
                log("Semáforo: Cambiando a " + currentGreen + " (ahora VERDE)");
                notifyLightChange();
            }
            log("Controlador de semáforos detenido.");
        }

        private void notifyLightChange() {
            synchronized (lightMonitor) {
                lightMonitor.notifyAll();
            }
            panel.setCurrentGreen(currentGreen);
        }

        private boolean sleepWithCheck(int ms) {
            try {
                int sleepInterval = 100; // Verificar cada 100ms
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

    private class Vehicle implements Runnable {
        final String id;
        final Direction dir;
        double x, y;
        volatile String state = "llegando";
        private final Point start;
        private final Point stop;
        private final Point exit;
        private final double step = 3.0;

        Vehicle(String id, Direction dir) {
            this.id = id;
            this.dir = dir;
            if (dir == Direction.NS) {
                start = new Point(340, -10);
                stop = new Point(340, 260);
                exit = new Point(340, 720);
            } else {
                start = new Point(-10, 340);
                stop = new Point(260, 340);
                exit = new Point(720, 340);
            }
            this.x = start.x;
            this.y = start.y;
        }

        @Override
        public void run() {
            try {
                Thread.sleep((long) (200 + Math.random() * 1200));
            } catch (InterruptedException e) {
                return;
            }

            log(id + " (" + dir + ") está aproximándose.");
            moveTo(stop);
            state = "esperando";
            log(id + " quiere cruzar - esperando luz " + dir);

            synchronized (lightMonitor) {
                while (currentGreen != dir) {
                    try {
                        lightMonitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            try {
                log(id + " trata de adquirir permiso para cruzar...");
                crossingSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            state = "cruzando";
            log(">>> " + id + " está cruzando (permiso adquirido).");
            moveTo(exit);
            crossingSemaphore.release();
            state = "salido";
            log("<<< " + id + " ha salido del cruce.");

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            removeVehicleFromModel(this);
        }

        private void moveTo(Point target) {
            double dx = target.x - x;
            double dy = target.y - y;
            double dist = Math.hypot(dx, dy);
            if (dist == 0)
                return;
            double vx = (dx / dist) * step;
            double vy = (dy / dist) * step;
            while (Math.hypot(target.x - x, target.y - y) > step) {
                x += vx;
                y += vy;
                try {
                    SwingUtilities.invokeLater(() -> panel.repaint());
                    Thread.sleep(25 + (int) (Math.random() * 40));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            x = target.x;
            y = target.y;
        }

        String getId() {
            return id;
        }

        Direction getDir() {
            return dir;
        }

        double getX() {
            return x;
        }

        double getY() {
            return y;
        }

        String getState() {
            return state;
        }
    }

    private class TrafficPanel extends JPanel {
        private Direction currentGreenLocal = currentGreen;

        void setCurrentGreen(Direction d) {
            this.currentGreenLocal = d;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());

            g.setColor(Color.GRAY);
            g.fillRect(300, 0, 80, getHeight());
            g.fillRect(0, 300, getWidth(), 80);

            g.setColor(new Color(70, 70, 70));
            g.fillRect(300, 300, 80, 80);

            g.setColor(Color.WHITE);
            for (int i = 0; i < 6; i++) {
                g.fillRect(320, 240 + i * 8, 10, 4);
                g.fillRect(240 + i * 8, 320, 4, 10);
            }

            drawSemaphore(g, 360, 200, "NS");
            drawSemaphore(g, 180, 360, "EW");

            try {
                guiLock.acquire();
                for (Vehicle v : vehicles) {
                    switch (v.getState()) {
                        case "llegando":
                            g.setColor(Color.WHITE);
                            break;
                        case "esperando":
                            g.setColor(Color.ORANGE);
                            break;
                        case "cruzando":
                            g.setColor(Color.GREEN);
                            break;
                        case "salido":
                            g.setColor(Color.LIGHT_GRAY);
                            break;
                        default:
                            g.setColor(Color.CYAN);
                            break;
                    }
                    int r = 8;
                    g.fillOval((int) v.getX() - r, (int) v.getY() - r, r * 2, r * 2);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Arial", Font.PLAIN, 10));
                    g.drawString(v.getId(), (int) v.getX() - 6, (int) v.getY() - 10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                guiLock.release();
            }

            g.setColor(Color.WHITE);
            g.drawString(
                    "Green: " + currentGreenLocal + "    Permits crossing: " + crossingSemaphore.availablePermits(), 10,
                    15);
            g.drawString("Vehículos generados: " + vehicleCounter + "/" + MAX_VEHICLES, 10, 30);
            g.drawString("Vehículos activos: " + activeVehicles, 10, 45);
            if (shouldStop) {
                g.setColor(Color.RED);
                g.drawString("SIMULACIÓN COMPLETADA", 10, 60);
            }
        }

        private void drawSemaphore(Graphics g, int sx, int sy, String label) {
            g.setColor(Color.BLACK);
            g.fillRect(sx, sy, 30, 70);

            // rojo
            g.setColor(currentGreenLocal == null ? Color.DARK_GRAY
                    : (currentGreenLocal == (label.equals("NS") ? Direction.EW : Direction.NS) ? Color.RED
                            : Color.DARK_GRAY));
            g.fillOval(sx + 5, sy + 5, 20, 20);
            // amarillo
            g.setColor(Color.DARK_GRAY);
            g.fillOval(sx + 5, sy + 28, 20, 20);
            // verde
            g.setColor(currentGreenLocal == (label.equals("NS") ? Direction.NS : Direction.EW) ? Color.GREEN
                    : Color.DARK_GRAY);
            g.fillOval(sx + 5, sy + 50, 20, 20);

            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.PLAIN, 10));
            g.drawString(label, sx + 4, sy + 68);
        }
    }

    private void log(String s) {
        System.out.printf("[%tT] %s%n", new Date(), s);
    }
}
