import numpy as np
f = 1000
fs = 16000
a = 0.97
# Pre-emphasis frequency response at f Hz
g = abs(1 - a * np.exp(-2j * np.pi * f / fs))
print(f"Pre-emphasis gain at {f}Hz: {g:.4f}")

# Simulate full chain: high-pass(150Hz) -> low-pass(7kHz) -> pre-emphasis(0.97)
x = np.sin(2 * np.pi * f * np.arange(fs) / fs).astype(np.float32) * 0.5

# High-pass 150 Hz
rc_hp = 1.0 / (2 * np.pi * 150)
dt = 1.0 / fs
a_hp = rc_hp / (rc_hp + dt)
hp_prev_x, hp_prev_y = 0.0, 0.0
hp_out = np.zeros_like(x)
for i in range(len(x)):
    hp = a_hp * (hp_prev_y + x[i] - hp_prev_x)
    hp_prev_x = float(x[i])
    hp_prev_y = hp
    hp_out[i] = hp

# Low-pass 7 kHz
rc_lp = 1.0 / (2 * np.pi * 7000)
a_lp = dt / (rc_lp + dt)
lp_prev = 0.0
lp_out = np.zeros_like(x)
for i in range(len(x)):
    lp = lp_prev + a_lp * (hp_out[i] - lp_prev)
    lp_prev = lp
    lp_out[i] = lp

# Pre-emphasis
pe_out = np.zeros_like(x)
pe_out[0] = lp_out[0]
for i in range(1, len(x)):
    pe_out[i] = lp_out[i] - 0.97 * lp_out[i-1]

peak_after = np.max(np.abs(pe_out[4000:]))
print(f"Peak after full chain at 1kHz (amp=0.5): {peak_after:.4f}")

# Try 20 Hz rumble
x20 = np.sin(2 * np.pi * 20 * np.arange(fs) / fs).astype(np.float32) * 0.5
hp_prev_x, hp_prev_y = 0.0, 0.0
hp20 = np.zeros_like(x20)
for i in range(len(x20)):
    hp = a_hp * (hp_prev_y + x20[i] - hp_prev_x)
    hp_prev_x = float(x20[i])
    hp_prev_y = hp
    hp20[i] = hp

lp_prev = 0.0
lp20 = np.zeros_like(x20)
for i in range(len(x20)):
    lp = lp_prev + a_lp * (hp20[i] - lp_prev)
    lp_prev = lp
    lp20[i] = lp

pe20 = np.zeros_like(x20)
pe20[0] = lp20[0]
for i in range(1, len(x20)):
    pe20[i] = lp20[i] - 0.97 * lp20[i-1]

peak20 = np.max(np.abs(pe20[4000:]))
print(f"Peak after full chain at 20Hz (amp=0.5): {peak20:.4f}")
