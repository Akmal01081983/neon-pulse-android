#!/usr/bin/env python3
# Короткие беззвучные петли упражнений для карточек приложения: один повтор, квадрат по фигуре, 360×360.
# Вес ~20–40 КБ против ~2 МБ у такого же GIF. Запуск: veo_loops.py <ключ1,ключ2>  (из media/<ключ>.mp4 → media/loop/<ключ>.mp4)
import json, os, subprocess, sys
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA = os.path.join(ROOT, 'media')


def loop(k):
    src = os.path.join(MEDIA, f'{k}.mp4')
    out = os.path.join(MEDIA, 'loop', f'{k}.mp4')
    W, H = map(int, subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height',
                                    '-of', 'csv=p=0', src], capture_output=True, text=True).stdout.strip().split(',')[:2])
    # Составной вертикальный ролик (сбоку сверху + спереди снизу) — берём вид сбоку
    pre = ''
    if H > W * 1.5:
        H //= 2
        pre = f'crop={W}:{H}:0:0,'
    w = 192
    h = round(192 * H / W / 2) * 2
    sc = W / w
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', src, '-vf', f'{pre}fps=24,scale={w}:{h},format=gray', '-f', 'rawvideo', '-'],
                         capture_output=True).stdout
    f = np.frombuffer(raw, np.uint8).reshape(-1, h, w).astype(np.float32)
    n = len(f)
    # Точки петли: два самых похожих кадра на расстоянии 2–5 секунд (один полный повтор)
    best = None
    for i in range(n):
        for j in range(i + 48, min(n, i + 120)):
            d = float(np.abs(f[i] - f[j]).mean())
            if best is None or d < best[0]:
                best = (d, i, j)
    if best is None:
        best = (0, 0, n - 1)
    # Рамка по фигуре: светлые пиксели за весь ролик, без подписи «Сбоку/Спереди» сверху
    mask = f.max(0) > 140
    mask[:int(h * 0.1)] = False
    cols, rows = np.where(mask.any(0))[0], np.where(mask.any(1))[0]
    if len(cols) and len(rows):
        x0, x1 = int(cols.min() * sc), int((cols.max() + 1) * sc)
        y0, y1 = int(rows.min() * sc), int((rows.max() + 1) * sc)
    else:
        x0, x1, y0, y1 = 0, W, 0, H
    side = int(min(W, H, max(x1 - x0, y1 - y0) + int(0.06 * max(W, H))))
    side -= side % 2
    X = int(min(W - side, max(0, (x0 + x1) // 2 - side // 2)))
    Y = int(min(H - side, max(0, (y0 + y1) // 2 - side // 2)))
    t0, t1 = best[1] / 24, best[2] / 24
    os.makedirs(os.path.dirname(out), exist_ok=True)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-ss', f'{t0:.3f}', '-to', f'{t1 - 1 / 24:.3f}', '-i', src, '-an',
                    '-vf', f'{pre}crop={side}:{side}:{X}:{Y},scale=360:360:flags=lanczos,fps=24',
                    '-c:v', 'libx264', '-crf', '28', '-preset', 'slow', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], check=True)
    print(k, f'{t0:.2f}–{t1:.2f} с', f'{os.path.getsize(out) // 1024} КБ')


if __name__ == '__main__':
    for k in [k for k in sys.argv[1].split(',') if k]:
        try:
            loop(k)
        except Exception as e:  # петля не обязательна: без неё приложение покажет полный ролик
            print(k, 'петля не получилась:', e)
