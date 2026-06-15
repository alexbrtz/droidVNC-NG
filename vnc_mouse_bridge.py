"""
vnc_mouse_bridge.py
Intercepta clicks del mouse dentro de la ventana VNC y los reenvía
al dispositivo Android via ADB como eventos touch.
"""

import subprocess
import sys
import pygetwindow as gw
from pynput import mouse

# ── Configuración ─────────────────────────────────────────────────────────────
ADB_DEVICE     = "10.163.16.16:5555"
ANDROID_WIDTH  = 1280
ANDROID_HEIGHT = 800

# Parte del título de la ventana de tu VNC viewer (case-insensitive)
# Ejemplos: "VNC", "TigerVNC", "RealVNC", "10.163.16.16"
VNC_WINDOW_TITLE = "VNC"
# ──────────────────────────────────────────────────────────────────────────────


def find_vnc_window():
    wins = [w for w in gw.getAllWindows()
            if VNC_WINDOW_TITLE.lower() in w.title.lower() and w.width > 0]
    if not wins:
        return None
    # Si hay varias, elegir la más grande
    return max(wins, key=lambda w: w.width * w.height)


def map_coords(mx, my, win):
    """Mapea coordenadas de pantalla a coordenadas del dispositivo Android."""
    rel_x = mx - win.left
    rel_y = my - win.top
    if rel_x < 0 or rel_y < 0 or rel_x > win.width or rel_y > win.height:
        return None, None
    ax = int(rel_x * ANDROID_WIDTH  / win.width)
    ay = int(rel_y * ANDROID_HEIGHT / win.height)
    return ax, ay


def adb_tap(ax, ay):
    subprocess.Popen(
        ["adb", "-s", ADB_DEVICE, "shell", "input", "tap", str(ax), str(ay)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def on_click(x, y, button, pressed):
    if not pressed:
        return

    win = find_vnc_window()
    if win is None:
        print(f"[!] Ventana VNC '{VNC_WINDOW_TITLE}' no encontrada")
        return

    ax, ay = map_coords(x, y, win)
    if ax is None:
        return  # click fuera de la ventana VNC

    if button == mouse.Button.left:
        adb_tap(ax, ay)
        print(f"[TAP]   PC({x},{y})  →  Android({ax},{ay})")

    elif button == mouse.Button.right:
        # Click derecho → long press (mantener 600ms)
        subprocess.Popen(
            ["adb", "-s", ADB_DEVICE, "shell", "input", "swipe",
             str(ax), str(ay), str(ax), str(ay), "600"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        print(f"[HOLD]  PC({x},{y})  →  Android({ax},{ay})")


def main():
    print("=" * 55)
    print("  VNC Mouse Bridge")
    print("=" * 55)
    print(f"  Dispositivo : {ADB_DEVICE}")
    print(f"  Resolución  : {ANDROID_WIDTH}x{ANDROID_HEIGHT}")
    print(f"  Ventana VNC : contiene '{VNC_WINDOW_TITLE}'")
    print("=" * 55)

    win = find_vnc_window()
    if win is None:
        print(f"\n[!] No se encontró ninguna ventana con '{VNC_WINDOW_TITLE}'.")
        print("    Abrí tu VNC viewer y volvé a ejecutar el script.")
        print("\n    Ventanas abiertas:")
        for w in gw.getAllWindows():
            if w.title.strip():
                print(f"      - {w.title}")
        sys.exit(1)

    print(f"\n[OK] Ventana detectada: '{win.title}'")
    print(f"     Posición : ({win.left}, {win.top})")
    print(f"     Tamaño   : {win.width}x{win.height}")
    print("\n  Click izquierdo → tap en Android")
    print("  Click derecho   → long press en Android")
    print("  Ctrl+C          → salir\n")

    with mouse.Listener(on_click=on_click) as listener:
        try:
            listener.join()
        except KeyboardInterrupt:
            print("\n[!] Saliendo...")


if __name__ == "__main__":
    main()