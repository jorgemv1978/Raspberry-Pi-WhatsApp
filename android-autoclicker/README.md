# AutoClicker para Android

App Android (APK instalable, sin root) para automatizar toques y gestos de todo tipo en cualquier app,
usando el servicio de accesibilidad de Android (`dispatchGesture`).

**APK listo para instalar:** [`release/AutoClicker.apk`](release/AutoClicker.apk) (Android 7.0+).

## Tipos de acciones

| Acción | Qué hace |
|---|---|
| Toque | Toque simple en (X, Y) con duración configurable |
| Doble toque | Dos toques rápidos |
| Pulsación larga | Mantener pulsado N ms |
| Deslizar | Swipe de (X, Y) a (X2, Y2) en N ms |
| Gesto libre (trazo) | Recorre una lista de puntos (se crea grabando) |
| Pellizcar / Separar dedos | Zoom con dos dedos |
| Toque con 2 dedos | Dos dedos a la vez |
| Pulsar elemento por texto / id | Busca un botón por su texto visible (o `id:com.app:id/boton`) y lo pulsa, esperando hasta N ms a que aparezca |
| Escribir texto | Rellena el campo de texto enfocado |
| Acción del sistema | Atrás, Inicio, Recientes, Notificaciones, Ajustes rápidos, Apagado, Bloquear, Captura |
| Abrir app | Lanza una app instalada por su paquete |
| Esperar | Pausa |

Cada acción tiene **repeticiones** y **pausa después**. El script tiene: repeticiones totales (0 = infinito),
pausa entre vueltas, **desplazamiento aleatorio** en píxeles, **variación aleatoria de pausas** (%) y
**parada automática** tras N minutos. Los scripts se pueden guardar, cargar, exportar e importar en JSON.

## Panel flotante

mantener pulsada la barra (o arrastrar `⠿`) para moverla · `▶/■` ejecutar/parar · `＋` añadir toque · `⇢` añadir deslizamiento ·
`●` grabar tus gestos (toques, dobles toques, pulsaciones largas, deslizamientos y trazos libres, con sus tiempos) ·
`⌫` borrar la última acción · `◎` mostrar/ocultar marcadores · `⚙` abrir la app · `✕` cerrar.

Arrastra los círculos numerados para colocar cada punto; **tócalos para editarlos** (tipo, coordenadas, duración, repeticiones, pausa) o eliminarlos. **Volumen-** detiene la ejecución en cualquier momento.

## Instalación

1. Copia `AutoClicker.apk` al móvil y ábrelo (permite «instalar apps de origen desconocido» si lo pide).
2. Abre AutoClicker → «Abrir ajustes de accesibilidad» → activa **AutoClicker**.
   En Android 13+, si aparece «Ajuste restringido»: Ajustes → Apps → AutoClicker → ⋮ → «Permitir ajustes restringidos», y vuelve a activarlo.
3. Pulsa «Mostrar panel flotante», añade o graba acciones y dale a ▶.

## Compilar

```bash
cd android-autoclicker
./gradlew assembleRelease   # requiere Android SDK (ANDROID_HOME o local.properties)
```

El workflow `.github/workflows/android-autoclicker.yml` compila el APK en GitHub Actions y lo publica como artefacto.
