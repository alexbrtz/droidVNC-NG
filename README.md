# droidVNC-NG — Rama Android 6 con soporte root

[![Únete al chat en Gitter](https://badges.gitter.im/droidVNC-NG/community.svg)](https://gitter.im/droidVNC-NG/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Este repositorio es un fork de [bk138/droidVNC-NG](https://github.com/bk138/droidVNC-NG), un servidor VNC para Android que utiliza las APIs modernas del sistema operativo para capturar la pantalla e inyectar eventos de entrada remotos.

La rama `android-6-root-support` extiende el proyecto original para funcionar en **dispositivos Android 6.0 (API 23)** con acceso root, eliminando por completo la necesidad de interacción del usuario al arrancar el dispositivo. Está pensada para entornos de tipo kiosco o gestión remota de dispositivos legacy.

---

## Diferencias respecto al proyecto original

El proyecto original requiere Android 7+ y que el usuario conceda manualmente los permisos cada vez que inicia. Esta rama resuelve ambas limitaciones:

| Componente | Cambio |
|---|---|
| `minSdkVersion` | Reducido de 24 a 23 (Android 6.0) |
| Inyección de entrada | Shell `su` persistente con `input tap/swipe` para API 23, en lugar de `GestureDescription` (exclusiva de API 24+) |
| `GestureHelper.java` | Nueva clase que aísla todo el código de API 24+ para evitar `VerifyError` en tiempo de ejecución en Android 6 |
| Permisos de accesibilidad | Se habilitan automáticamente vía root en cada boot, sin necesidad de que el usuario entre a Ajustes |
| Permiso de escritura en almacenamiento | Saltado automáticamente cuando se detecta acceso root |
| Diálogo MediaProjection | Auto-aprobado por un hilo en segundo plano usando `dumpsys activity` + `uiautomator dump` con fallback a `KEYCODE_ENTER` |
| `OnBootReceiver` | Simplificado: configura accesibilidad vía root y delega toda la lógica de permisos a `MainService` |
| Bloqueo de pantalla | Debe estar deshabilitado (ver preparación inicial) |
| `vnc_mouse_bridge.py` | Script Python opcional para reenviar clics del mouse del PC al dispositivo Android vía ADB |

---

## Requisitos

- Dispositivo Android 6.0 con **acceso root** (comando `su` disponible)
- Conexión ADB al dispositivo (USB o red)
- Pantalla de bloqueo deshabilitada (ver preparación inicial)
- La opción **"Iniciar al encender"** activada en los ajustes de la app (está activada por defecto)

---

## Preparación inicial

Estos pasos se ejecutan una sola vez después de instalar la APK.

### 1. Instalar la APK

Compilar desde Android Studio o con Gradle:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Deshabilitar el bloqueo de pantalla

El bloqueo de pantalla impide que el diálogo de MediaProjection aparezca en el momento del boot. Ejecutar una sola vez:

```bash
adb shell su -c "settings put secure lockscreen.disabled 1"
adb shell su -c "settings put secure lockscreen.password_type 0"
adb shell su -c "rm -f /data/system/password.key /data/system/gesture.key /data/system/pattern.key"
```

### 3. Habilitar el servicio de accesibilidad

El servicio de accesibilidad (`InputService`) se habilita automáticamente en cada boot vía root. Para habilitarlo manualmente la primera vez:

```bash
adb shell su -c "settings put secure enabled_accessibility_services net.christianbeier.droidvnc_ng/.InputService"
adb shell su -c "settings put secure accessibility_enabled 1"
```

### 4. Iniciar el servidor VNC manualmente (primera vez)

```bash
adb shell am startservice -n net.christianbeier.droidvnc_ng/.MainService -a start
```

A partir del siguiente reinicio, todo ocurre de forma automática.

---

## Flujo de arranque automático

Cuando el dispositivo reinicia, la secuencia es completamente desatendida:

1. **`OnBootReceiver`** recibe `BOOT_COMPLETED`, habilita el servicio de accesibilidad vía root e inicia `MainService`.
2. **`MainService`** detecta acceso root y omite el diálogo de permisos de entrada y de escritura en almacenamiento.
3. **`MainService`** solicita el permiso MediaProjection al sistema y lanza un hilo en segundo plano que:
   - Despierta la pantalla (`KEYCODE_WAKEUP`)
   - Detecta el diálogo del sistema mediante `dumpsys activity top`
   - Obtiene las coordenadas del botón de confirmación con `uiautomator dump`
   - Si `uiautomator` falla, presiona `KEYCODE_ENTER` como fallback
4. El servidor VNC arranca y queda disponible para conexiones entrantes. **Sin ninguna interacción del usuario.**

---

## Conexión desde un cliente VNC

Por defecto el servidor escucha en el puerto **5900**. Conectarse con cualquier cliente VNC compatible (RealVNC, TigerVNC, TightVNC, etc.):

```
<IP del dispositivo>:5900
```

Para obtener la IP del dispositivo:

```bash
adb shell ip route | grep wlan
```

---

## Script vnc_mouse_bridge.py (opcional)

Herramienta de escritorio que captura los clics del mouse sobre la ventana del visor VNC y los reenvía como toques al dispositivo Android vía ADB, sin depender del cliente VNC para la inyección de entrada.

**Requisitos:**

```bash
pip install pynput pygetwindow
```

**Uso:**

```bash
python vnc_mouse_bridge.py
```

El script detecta automáticamente la ventana del visor VNC por su título, convierte las coordenadas al tamaño de pantalla del dispositivo y ejecuta `adb shell input tap X Y` para cada clic.

---

## Solución de problemas

### Pantalla negra al conectarse por VNC

- Verificar que el bloqueo de pantalla esté deshabilitado.
- Revisar los logs del servicio para confirmar que MediaProjection fue aprobado:
  ```bash
  adb logcat -s MainService:D | grep -E "autoApprove|image available"
  ```
- Si aparece `dialog never appeared`, intentar iniciar el servicio manualmente y aprobar el diálogo desde ADB:
  ```bash
  adb shell su -c "input keyevent KEYCODE_WAKEUP"
  ```

### El servicio de accesibilidad no se habilita

Verificar que el dispositivo tenga acceso root real:
```bash
adb shell su -c "id"
# Debe mostrar: uid=0(root)
```

### El servidor no inicia en el boot

Confirmar que `RECEIVE_BOOT_COMPLETED` está permitido para la app:
```bash
adb shell pm grant net.christianbeier.droidvnc_ng android.permission.RECEIVE_BOOT_COMPLETED
```

---

## Créditos

- Proyecto original: [bk138/droidVNC-NG](https://github.com/bk138/droidVNC-NG) — Christian Beier
- Base de este fork: [EvgeniySpinov/droidVNC-NG](https://github.com/EvgeniySpinov/droidVNC-NG)
- Soporte Android 6 + root: [alexbrtz](https://github.com/alexbrtz)
