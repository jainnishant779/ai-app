import numpy as np

fs = 16000
for alpha in [0.97, 0.90, 0.80, 0.70, 0.50]:
    gains = {}
    for f in [20, 100, 300, 500, 1000, 2000, 3000, 4000]:
        g = abs(1 - alpha * np.exp(-2j * np.pi * f / fs))
        gains[f] = g
    print(f"alpha={alpha:.2f}: " + "  ".join(f"{f}Hz={g:.3f}" for f, g in gains.items()))
