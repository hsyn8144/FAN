# -*- coding: utf-8 -*-
"""
FAN SUPER — Python Meclisi (Chaquopy ile uygulamaya gömülü).
Kotlin tarafı yalnızca bu modüldeki fonksiyonları çağırır; tüm giriş/çıkış JSON metnidir.
"""
import copy
import json
import math
import time
from collections import deque

import numpy as np

import fan_members as fm

K = 4


class Rolling:
    def __init__(self, cap):
        self.buf = deque(maxlen=cap)

    def add(self, b):
        self.buf.append(1 if b else 0)

    @property
    def n(self):
        return len(self.buf)

    def rate(self):
        return (sum(self.buf) / len(self.buf)) if self.buf else 0.0


class PyCouncil:
    def __init__(self, cfg):
        self.cfg = cfg
        self.members = fm.all_members()
        n = len(self.members)
        self.w = np.full(n, 1.0 / n)
        win = int(cfg.get("window", 100))
        self.r1 = [Rolling(win) for _ in range(n)]
        self.r2 = [Rolling(win) for _ in range(n)]
        self.bench = [False] * n
        self.vals = []
        self.times = []
        self.last_preds = None
        self.last_mix = np.full(K, 0.25)
        self.step_no = 0
        self.last_ms = 0.0
        self.per = []  # her kayıt için, o kayıt gelmeden önceki meclis tahmini
        self._apply_cfg()

    def _apply_cfg(self):
        dl = bool(self.cfg.get("dl", True))
        battery = bool(self.cfg.get("battery", False))
        for m in self.members:
            if hasattr(m, "slow"):
                m.slow = 2 if battery else 1
        self.dl = dl
        self.battery = battery

    def enabled(self, i):
        m = self.members[i]
        if m.id in self.cfg.get("disabled", []):
            return False
        if getattr(m, "dl", False) and not self.dl:
            return False
        return True

    def _hist(self):
        return fm.Hist(np.asarray(self.vals, dtype=np.int64), np.asarray(self.times, dtype=np.int64), len(self.vals))

    def predict(self):
        h = self._hist()
        preds = []
        for i, m in enumerate(self.members):
            if not self.enabled(i):
                preds.append(np.full(K, 0.25))
                continue
            try:
                preds.append(fm.norm(m.predict(h)))
            except Exception:
                preds.append(np.full(K, 0.25))
        self.last_preds = preds
        act = [self.enabled(i) and not self.bench[i] for i in range(len(self.members))]
        if not any(act):
            act = [self.enabled(i) for i in range(len(self.members))]
        mix = np.zeros(K)
        tw = 0.0
        for i, p in enumerate(preds):
            if act[i]:
                mix += self.w[i] * p
                tw += self.w[i]
        self.last_mix = fm.norm(mix / tw) if tw > 0 else np.full(K, 0.25)
        return self.last_mix

    def learn(self, v, t):
        """Gerçek sonucu öğren (predict() önce çağrılmış olmalı)."""
        t0 = time.time()
        preds = self.last_preds
        self.per.append([float(x) for x in self.last_mix])
        self.vals.append(int(v))
        self.times.append(int(t))
        self.step_no += 1
        win = int(self.cfg.get("window", 100))
        thr = float(self.cfg.get("bench", 0.20))
        alpha = float(self.cfg.get("alpha", 0.02))
        if preds is not None:
            for i, p in enumerate(preds):
                o = np.argsort(-p)
                self.r1[i].add(o[0] == v)
                self.r2[i].add(o[0] == v or o[1] == v)
                self.bench[i] = self.r1[i].n >= win and self.r1[i].rate() < thr
                self.w[i] *= math.exp(-0.6 * -math.log(max(p[v], 1e-9)))
            s = self.w.sum()
            if not np.isfinite(s) or s <= 0:
                self.w[:] = 1.0 / len(self.w)
            else:
                self.w /= s
            n = len(self.w)
            self.w = (1 - alpha) * self.w + alpha / n
        h = self._hist()
        heavy_ok = (not self.battery) or (self.step_no % 5 == 0)
        for i, m in enumerate(self.members):
            if not self.enabled(i):
                continue
            if getattr(m, "dl", False):
                m.train = heavy_ok
            try:
                m.update(h)
            except Exception:
                pass
        self.last_ms = (time.time() - t0) * 1000.0

    def stats(self):
        out = []
        n = len(self.members)
        for i, m in enumerate(self.members):
            out.append({
                "id": m.id, "name": m.name,
                "top1": self.r1[i].rate(), "top2": self.r2[i].rate(),
                "weight": float(self.w[i] * n), "benched": bool(self.bench[i]),
                "enabled": bool(self.enabled(i)), "n": self.r1[i].n,
            })
        return out


# ------------------------------------------------------------------ modül durumu
_council = None
_cfg = {}
_snaps = deque(maxlen=8)


def _lst(p):
    return [float(x) for x in p]


def configure(cfg_json):
    global _cfg
    _cfg = json.loads(cfg_json) if cfg_json else {}
    return "ok"


def replay(values_json, times_json):
    """Tüm geçmişi baştan öğren. Dönüş: {"per": [...], "next": [...]}"""
    global _council
    vals = json.loads(values_json)
    times = json.loads(times_json)
    _council = PyCouncil(_cfg)
    _snaps.clear()
    t0 = time.time()
    for v, t in zip(vals, times):
        _council.predict()
        _council.learn(int(v), int(t))
    nxt = _council.predict()
    _council.last_ms = (time.time() - t0) * 1000.0 / max(1, len(vals))
    return json.dumps({"per": _council.per, "next": _lst(nxt)})


def step(v, t):
    global _council
    if _council is None:
        _council = PyCouncil(_cfg)
        _council.predict()
    _snaps.append(copy.deepcopy(_council))
    _council.learn(int(v), int(t))
    return json.dumps(_lst(_council.predict()))


def undo():
    global _council
    if not _snaps:
        return ""
    _council = _snaps.pop()
    return json.dumps(_lst(_council.last_mix))


def stats():
    if _council is None:
        return "[]"
    return json.dumps(_council.stats())


def info():
    if _council is None:
        return json.dumps({"status": "hazırlanıyor"})
    return json.dumps({
        "status": "çalışıyor",
        "ms": round(_council.last_ms, 1),
        "n": len(_council.vals),
        "numpy": np.__version__,
        "dl": _council.dl,
    })


def save_state(path):
    """Öğrenilmiş meclisi diske kaydeder (açılışta yeniden öğrenmemek için)."""
    import pickle
    if _council is None:
        return "yok"
    with open(path, "wb") as f:
        pickle.dump({"council": _council, "cfg": _cfg}, f, protocol=pickle.HIGHEST_PROTOCOL)
    return "ok"


def load_state(path, values_json, times_json):
    """Kayıtlı durum mevcut veriyle birebir aynıysa yükler. Dönüş: replay() ile aynı biçim ya da ""."""
    global _council
    import os
    import pickle
    if not os.path.exists(path):
        return ""
    try:
        with open(path, "rb") as f:
            d = pickle.load(f)
        c = d["council"]
        vals = json.loads(values_json)
        times = json.loads(times_json)
        if c.vals != [int(x) for x in vals] or c.times != [int(x) for x in times] or d.get("cfg") != _cfg:
            return ""
        _council = c
        _snaps.clear()
        return json.dumps({"per": _council.per, "next": _lst(_council.predict())})
    except Exception:
        return ""
