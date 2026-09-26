# -*- coding: utf-8 -*-
"""Python meclisi geri alma (undo) testleri — CI'da ve yerelde çalışır.

Çalıştırma:
    pip install "numpy<2"
    cd app/src/main/python && python -m unittest discover -s ../../test/python -t . -v
"""
import json
import os
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PY_SRC = os.path.normpath(os.path.join(HERE, "..", "..", "main", "python"))
if PY_SRC not in sys.path:
    sys.path.insert(0, PY_SRC)

import fan_super as fs  # noqa: E402

CSV = os.path.normpath(os.path.join(HERE, "..", "..", "main", "assets", "fan_data_live.csv"))


def load_records(limit=None):
    """assets/fan_data_live.csv → (values, times) (Kotlin DataStore.parse ile aynı mantık)."""
    vals, times = [], []
    with open(CSV, encoding="utf-8-sig") as f:
        lines = [ln.strip() for ln in f if ln.strip()]
    head = lines[0].split("|")
    vi = head.index("sayi")
    ti = head.index("tarih")
    for ln in lines[1:]:
        c = ln.split("|")
        v = int(c[vi])
        if v not in (1, 2, 3, 4):
            continue
        t = int(time.mktime(time.strptime(c[ti], "%Y-%m-%d %H:%M:%S")))
        vals.append(v - 1)          # motor 0..3 kullanır
        times.append(t)
        if limit and len(vals) >= limit:
            break
    return vals, times


def replay(vals, times, cfg=None):
    fs.configure(json.dumps(cfg or {"window": 100}))
    return json.loads(fs.replay(json.dumps(vals), json.dumps(times)))


class UndoTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.vals, cls.times = load_records(220)
        assert len(cls.vals) > 120, "test verisi yetersiz"

    def test_01_replay_shape(self):
        r = replay(self.vals, self.times)
        self.assertEqual(len(r["per"]), len(self.vals))
        self.assertEqual(len(r["next"]), 4)
        for p in r["per"] + [r["next"]]:
            self.assertAlmostEqual(sum(p), 1.0, places=6)

    def test_02_step_then_undo_is_exact_and_fast(self):
        r = replay(self.vals, self.times)
        n = len(self.vals)
        nxt = r["next"]

        t0 = time.time()
        fs.step(2, self.times[-1] + 5)
        step_ms = (time.time() - t0) * 1000.0

        t0 = time.time()
        back = json.loads(fs.undo_to(n))
        undo_ms = (time.time() - t0) * 1000.0

        # Geri alma, adım öncesi tahmini BİREBİR vermeli (veri bozulmaz).
        for a, b in zip(nxt, back):
            self.assertAlmostEqual(a, b, places=12)
        # Adım ve geri alma derin kopya yapmadığı için hızlı olmalı.
        self.assertLess(step_ms, 4000.0, "step çok yavaş: %.1f ms" % step_ms)
        self.assertLess(undo_ms, 4000.0, "undo çok yavaş: %.1f ms" % undo_ms)
        self.assertEqual(json.loads(fs.stats()).__len__(), 10)
        self.assertEqual(json.loads(fs.info())["n"], n)

    def test_03_undo_step_by_step_returns_previous_predictions(self):
        r = replay(self.vals, self.times)
        n = len(self.vals)
        preds = [r["next"]]                    # preds[i] = n+i kayıt bilinen tahmin
        for i in range(6):
            preds.append(json.loads(fs.step(i % 4, self.times[-1] + 10 + i)))
        # 6 adımı tek tek geri al: her undo_to(k) o andaki tahmini birebir vermeli
        for k in range(n + 5, n - 1, -1):
            got = json.loads(fs.undo_to(k))
            for a, b in zip(preds[k - n], got):
                self.assertAlmostEqual(a, b, places=12, msg="undo_to(%d) yanlış tahmin" % k)
        self.assertEqual(json.loads(fs.info())["n"], n)

    def test_04_deep_undo_matches_fresh_replay(self):
        replay(self.vals, self.times)
        n = len(self.vals)
        for i in range(5):                       # canlı adımlar
            fs.step(i % 4, self.times[-1] + 30 + i)
        target = n - 37                          # dayanak üzerinden ileri sarım gerektirir
        t0 = time.time()
        back = fs.undo_to(target)
        deep_ms = (time.time() - t0) * 1000.0
        self.assertTrue(back, "derin geri alma başarısız")
        ref = replay(self.vals[:target], self.times[:target])
        for a, b in zip(ref["next"], json.loads(back)):
            self.assertAlmostEqual(a, b, places=9)
        self.assertLess(deep_ms, 30000.0, "derin undo çok yavaş: %.1f ms" % deep_ms)

    def test_05_state_file_keeps_undo_fast(self):
        replay(self.vals, self.times)
        n = len(self.vals)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "py_state.pkl")
            self.assertEqual(fs.save_state(p), "ok")
            self.assertTrue(os.path.getsize(p) > 0)
            # Yeni süreç gibi: modül durumunu sıfırla ve dosyadan yükle.
            fs._council = None
            fs._journal.clear()
            fs._checkpoints.clear()
            out = fs.load_state(p, json.dumps(self.vals), json.dumps(self.times))
            self.assertTrue(out, "load_state doğrulamayı geçemedi")
            self.assertEqual(len(json.loads(out)["per"]), n)
            t0 = time.time()
            back = fs.undo_to(n - 3)
            ms = (time.time() - t0) * 1000.0
            self.assertTrue(back, "yükleme sonrası geri alma başarısız")
            ref = replay(self.vals[:n - 3], self.times[:n - 3])
            for a, b in zip(ref["next"], json.loads(back)):
                self.assertAlmostEqual(a, b, places=9)
            self.assertLess(ms, 30000.0, "yükleme sonrası undo çok yavaş: %.1f ms" % ms)

    def test_06_wrong_data_rejected(self):
        replay(self.vals[:50], self.times[:50])
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "s.pkl")
            fs.save_state(p)
            self.assertEqual(fs.load_state(p, json.dumps(self.vals[:40]), json.dumps(self.times[:40])), "")

    def test_07_undo_beyond_history_is_empty(self):
        replay(self.vals[:40], self.times[:40])
        self.assertEqual(fs.undo_to(41), "")
        self.assertEqual(fs.undo_to(-1), "")
        self.assertTrue(fs.undo_to(40))          # aynı hedef: tahmini döndürür


if __name__ == "__main__":
    unittest.main(verbosity=2)
