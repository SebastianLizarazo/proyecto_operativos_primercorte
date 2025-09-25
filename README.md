# Simulador de Tráfico con Semáforos V2

## Autores

- Sebastian David Lizarazo Duarte
- Andrés Felipe Luna

## Enlace del Proyecto

https://github.com/SebastianLizarazo/proyecto_operativos_primercorte.git

## 📋 Descripción

`TrafficSemaphoreSimulationV2.java` es una implementación monolítica completa de un simulador de tráfico vehicular que utiliza semáforos para controlar el flujo en una intersección. Este archivo único contiene todas las clases necesarias para la simulación, incluyendo la lógica de control, la interfaz gráfica y la gestión de vehículos.

## 🚀 Ejecución Rápida

### Verificar Compatibilidad de Java

**⚠️ IMPORTANTE**: Asegúrate de usar la misma versión de Java para compilar y ejecutar:

```bash
# Verificar versiones (deben ser iguales)
javac -version    # Versión del compilador
java -version     # Versión del runtime

# Ejemplo de salida correcta:
# javac 1.8.0_XXX
# java version "1.8.0_XXX"
```

### Compilar y Ejecutar (Opción Estándar)

```bash
# Compilar
javac TrafficSemaphoreSimulationV2.java

# Ejecutar
java TrafficSemaphoreSimulationV2
```

### Compilar y Ejecutar en Carpeta COM (Recomendado)

```bash
# Compilar creando carpeta COM y guardando archivos .class allí
mkdir COM && javac -d COM TrafficSemaphoreSimulationV2.java

# Ejecutar desde la carpeta COM
java -cp COM TrafficSemaphoreSimulationV2
```

> **💡 Ventaja de usar carpeta COM**: Mantiene organizados los archivos compilados (.class) separados del código fuente (.java), facilitando la limpieza y distribución del proyecto.

### Estructura de Archivos Resultante

Después de compilar con la opción COM, tendrás la siguiente estructura:

```
proyecto_primercorte/
├── TrafficSemaphoreSimulationV2.java    # Código fuente
├── README_V2.md                         # Esta documentación
└── COM/                                 # Carpeta de archivos compilados
    ├── TrafficSemaphoreSimulationV2.class
    ├── TrafficSemaphoreSimulationV2$Direction.class
    ├── TrafficSemaphoreSimulationV2$TrafficLightController.class
    ├── TrafficSemaphoreSimulationV2$Vehicle.class
    └── TrafficSemaphoreSimulationV2$TrafficPanel.class
```

### Limpieza de Archivos Compilados

```bash
# Para limpiar los archivos compilados
rmdir /s COM    # Windows
# o
rm -rf COM      # Linux/Mac
```

## 🏗️ Estructura del Archivo

### Clases Principales

1. **TrafficSemaphoreSimulationV2** (Clase Principal)

   - Gestiona la simulación completa
   - Coordina todos los componentes
   - Maneja la creación de hilos

2. **Direction** (Enum)

   ```java
   enum Direction {
       NorteSur, EsteOeste  // Norte-Sur, Este-Oeste
   }
   ```

3. **TrafficLightController** (Clase Interna)

   - Controla el ciclo de los semáforos
   - Alterna entre direcciones NorteSur y EsteOeste
   - Maneja tiempos de verde y amarillo

4. **Vehicle** (Clase Interna)

   - Representa cada vehículo individual
   - Ejecuta en su propio hilo
   - Implementa estados: llegando, esperando, cruzando, salido

5. **TrafficPanel** (Clase Interna)
   - Interfaz gráfica Swing
   - Visualización en tiempo real
   - Dibuja calles, semáforos y vehículos

## ⚙️ Configuración del Sistema

### Parámetros Principales

```java
private final int GREEN_MS = 4000;        // 4 segundos en verde
private final int YELLOW_MS = 2000;       // 2 segundos en amarillo
private final int SPAWN_MS = 1000;        // 1 segundo entre vehículos
private final int MAX_VEHICLES = 20;      // Máximo 20 vehículos
```

### Semáforos de Control

```java
private final Semaphore crossingSemaphore = new Semaphore(3);  // Máx 3 vehículos cruzando
private final Semaphore guiLock = new Semaphore(1);           // Exclusión mutua GUI
```

## 🔧 Conceptos de Concurrencia

### 1. Semáforo de Conteo

- **crossingSemaphore**: Limita a 3 vehículos cruzando simultáneamente
- Previene congestión en la intersección
- Garantiza flujo controlado

### 2. Semáforo Binario

- **guiLock**: Exclusión mutua para acceso a la GUI
- Protege la lista de vehículos
- Evita condiciones de carrera en la visualización

### 3. Monitor Object Pattern

- **lightMonitor**: Sincroniza cambios de semáforo
- Los vehículos esperan hasta tener luz verde
- Notificación broadcast cuando cambia la luz

### 4. Estados de Vehículo

- **"llegando"**: Se aproxima a la intersección (Blanco)
- **"esperando"**: Detenido en luz roja (Naranja)
- **"cruzando"**: Atravesando la intersección (Verde)
- **"salido"**: Ha completado el cruce (Gris claro)

## 🎮 Interfaz Gráfica

### Elementos Visuales

#### Calles

- **Calle vertical**: Dirección Norte-Sur (gris)
- **Calle horizontal**: Dirección Este-Oeste (gris)
- **Intersección central**: Área de cruce (gris oscuro)
- **Líneas divisorias**: Marcas blancas discontinuas

#### Semáforos

- **Posición NorteSur**: Esquina superior derecha
- **Posición EsteOeste**: Esquina inferior izquierda
- **Luces**: Roja (arriba), Amarilla (medio), Verde (abajo)
- **Estado visual**: Solo una luz encendida por vez

#### Vehículos

- **Representación**: Círculos de 8px de radio
- **Identificación**: Etiqueta con ID del vehículo
- **Colores por estado**:
  - 🔵 **Blanco**: Aproximándose
  - 🟠 **Naranja**: Esperando en luz roja
  - 🟢 **Verde**: Cruzando la intersección
  - ⚪ **Gris claro**: Salido del sistema

### Panel de Información

```
Green: NorteSur    Permits crossing: 2
Vehículos generados: 15/20
Vehículos activos: 8
SIMULACIÓN COMPLETADA (cuando termina)
```

## 🔄 Flujo de Ejecución

### 1. Inicialización

```java
public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> new TrafficSemaphoreSimulationV2().start());
}
```

### 2. Creación de Hilos

- **Hilo principal**: Gestión de GUI (Swing EDT)
- **TrafficLightController**: Control de semáforos
- **Spawner**: Generación periódica de vehículos
- **Vehicle threads**: Un hilo por cada vehículo (hasta 20)

### 3. Ciclo de Semáforos

```
NS Verde (4s) → Amarillo (2s) → EW Verde (4s) → Amarillo (2s) → Repetir
```

### 4. Generación de Vehículos

- Intervalo base: 1 segundo + variación aleatoria (0-800ms)
- Dirección aleatoria: 50% NS, 50% EW
- Límite: 20 vehículos total

### 5. Terminación Automática

- Se detiene cuando todos los vehículos completan su trayecto
- Los semáforos se detienen automáticamente
- Mensaje "SIMULACIÓN COMPLETADA" en pantalla

## 📊 Logging del Sistema

### Formato de Mensajes

```
[HH:mm:ss] Mensaje del sistema
```

### Eventos Registrados

- Cambios de estado de semáforos
- Creación y eliminación de vehículos
- Solicitudes de permisos de cruce
- Inicio y fin de simulación

### Ejemplo de Log

```
[10:15:23] Semáforo: NS -> VERDE
[10:15:24] V1 (NS) está aproximándose.
[10:15:25] V1 quiere cruzar - esperando luz NS
[10:15:26] >>> V1 está cruzando (permiso adquirido).
[10:15:27] Semáforo: NS -> AMARILLO
[10:15:29] Semáforo: Cambiando a EW (ahora VERDE)
[10:15:30] <<< V1 ha salido del cruce.
```

## 🎯 Características Principales

### ✅ Funcionalidades Implementadas

- ✅ Control de semáforos con tiempos configurables
- ✅ Límite máximo de vehículos (20)
- ✅ Máximo 3 vehículos cruzando simultáneamente
- ✅ Generación aleatoria de vehículos
- ✅ Estados visuales diferenciados por colores
- ✅ Terminación automática de la simulación
- ✅ Interfaz gráfica responsiva (40ms de actualización)
- ✅ Logging detallado de eventos
- ✅ Sincronización thread-safe

### 🔒 Seguridad de Concurrencia

- **Sin condiciones de carrera**: Uso apropiado de semáforos
- **Sin deadlocks**: Orden consistente de adquisición de locks
- **Sin inanición**: Semáforos fair (FIFO)
- **Terminación limpia**: Manejo de interrupciones

## 🚗 Lógica de Movimiento de Vehículos

### Posiciones Clave

```java
// Vehículos Norte-Sur
start = Point(340, -10);    // Fuera de pantalla (arriba)
stop = Point(340, 260);     // Antes de la intersección
exit = Point(340, 720);     // Fuera de pantalla (abajo)

// Vehículos Este-Oeste
start = Point(-10, 340);    // Fuera de pantalla (izquierda)
stop = Point(260, 340);     // Antes de la intersección
exit = Point(720, 340);     // Fuera de pantalla (derecha)
```

### Algoritmo de Movimiento

1. **Aproximación**: Movimiento desde `start` hasta `stop`
2. **Espera**: Sincronización con monitor de luz
3. **Solicitud**: Adquisición de permiso de cruce
4. **Cruce**: Movimiento desde `stop` hasta `exit`
5. **Liberación**: Liberación de permiso y limpieza

## 🔧 Personalización

### Modificar Tiempos

```java
private final int GREEN_MS = 6000;    // Cambiar a 6 segundos verde
private final int YELLOW_MS = 1500;   // Cambiar a 1.5 segundos amarillo
```

### Cambiar Límites

```java
private final int MAX_VEHICLES = 50;           // Aumentar a 50 vehículos
private final Semaphore crossingSemaphore = new Semaphore(5); // Permitir 5 cruzando
```

### Ajustar Velocidad

```java
private final double step = 5.0;    // Aumentar velocidad de vehículos
private final int SPAWN_MS = 500;   // Generar vehículos más rápido
```

## 🐛 Solución de Problemas

### Problema: "UnsupportedClassVersionError" o "class file version"

**Error completo**:

```
Exception in thread "main" java.lang.UnsupportedClassVersionError:
TrafficSemaphoreSimulationV2 has been compiled by a more recent version
of the Java Runtime (class file version 65.0), this version of the Java
Runtime only recognizes class file versions up to 52.0
```

**Causa**: Compilaste con una versión más reciente de Java de la que tienes para ejecutar.

**Soluciones**:

1. **Usar la misma versión para compilar y ejecutar** (Recomendado):

   ```bash
   # Verificar versión de Java para compilar
   javac -version

   # Verificar versión de Java para ejecutar
   java -version

   # Ambas deben ser la misma versión
   ```

2. **Recompilar con la versión de Java disponible**:

   ```bash
   # Limpiar archivos compilados anteriores
   rmdir /s COM    # Windows
   # o rm -rf COM  # Linux/Mac

   # Recompilar con la versión correcta de Java
   mkdir COM && javac -d COM TrafficSemaphoreSimulationV2.java

   # Ejecutar
   java -cp COM TrafficSemaphoreSimulationV2
   ```

3. **Actualizar Java Runtime Environment**:
   - Instalar la misma versión de JRE que tienes de JDK
   - O usar una distribución completa como OpenJDK

**Versiones de Java y Class File Version**:

- Java 8: version 52.0
- Java 11: version 55.0
- Java 17: version 61.0
- Java 21: version 65.0

### Problema: "Could not find or load main class"

**Solución**:

- Si compilaste con carpeta COM, asegúrate de usar: `java -cp COM TrafficSemaphoreSimulationV2`
- Si compilaste normalmente, usa: `java TrafficSemaphoreSimulationV2`

### Problema: Vehículos no se mueven

**Solución**: Verificar que los hilos no estén bloqueados esperando permisos

### Problema: GUI no se actualiza

**Solución**: Asegurar que `guiLock` se libere correctamente

### Problema: Simulación no termina

**Solución**: Verificar que `activeVehicles` se decremente correctamente

### Problema: Excepción de concurrencia

**Solución**: Revisar que todos los accesos a recursos compartidos usen semáforos

### Problema: La carpeta COM no se crea

**Solución**:

- En Windows: `mkdir COM` o usar `md COM`
- En Linux/Mac: `mkdir COM`
- O dejar que `javac -d COM` la cree automáticamente

## 📈 Métricas de Rendimiento

## 📚 Conceptos Educativos Demostrados

### Sistemas Operativos

- **Semáforos**: Conteo y binario
- **Exclusión mutua**: Protección de recursos compartidos
- **Sincronización**: Coordinación entre hilos
- **Monitores**: Pattern para sincronización compleja

### Programación Concurrente

- **Hilos**: Creación y gestión de threads
- **Variables volátiles**: Visibilidad entre hilos
- **Interrupción**: Manejo graceful de terminación
- **Estados**: Máquina de estados en entorno concurrente

### Patrones de Diseño

- **Observer**: Notificación de cambios de estado
- **State**: Estados de vehículos
- **Facade**: Interfaz simplificada del sistema
- **Template Method**: Estructura común de vehículos

## ObjetivoCumplidos

1. ✅ **Aplicar semáforos de conteo y binarios**
2. ✅ **Implementar sincronización entre hilos**
3. ✅ **Prevenir condiciones de carrera**
4. ✅ **Gestionar recursos compartidos**
5. ✅ **Crear simulación visual**
6. ✅ **Implementar terminación controlada**
7. ✅ **Manejar estados concurrentes**
8. ✅ **Aplicar patrones de concurrencia**

---

## 💻 Información Técnica

**Archivo**: `TrafficSemaphoreSimulationV2.java`  
**Versión**: 2.0  
**Lenguaje**: Java 8+  
**Framework GUI**: Swing  
**Líneas de código**: ~350  
**Clases**: 4 clases + 1 enum  
**Paradigma**: Programación Orientada a Objetos + Concurrencia
