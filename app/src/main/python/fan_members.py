# -*- coding: utf-8 -*-
"""
FAN SUPER — Python Meclisi üyeleri.
Tüm değerler 0..3 (kullanıcı sayısı 1..4). Her üye:
    predict(h) -> np.ndarray(4)   (h.n kayıt bilinirken sonraki için dağılım)
    update(h)                     (h.n kayıt; son kayıt yeni gelen gerçek sonuç)

Geri alma (undo) protokolü — fan_super.py hızlı geri alma için kullanır:
    snap()         -> adım öncesi durumun UCUZ kaydı (büyük tablolar kopyalanmaz)
    can_restore(s) -> bu kayıt hâlâ geri yüklenebilir mi
    restore(s)     -> kayda geri dön, başarı için True
Bu üçlüyü tanımlamayan üyeler için `dict(m.__dict__)` sığ kopyası kullanılır.
Bu yüzden hiçbir üye paylaşılan numpy dizilerini / listeleri / sözlükleri
YERİNDE değiştirmemeli, her zaman yeni bir nesne atamalıdır.
"""
import math
import numpy as np

K = 4

# Tablo tutan üyelerin (MotifDiscovery) geri alma günlüğü üst sınırı.
UNDO_LOG_MAX = 20000


def norm(p, floor=1e-4):
    p = np.asarray(p, dtype=float)
    p = np.where(np.isfinite(p), p, floor)
    p = np.maximum(p, floor)
    return p / p.sum()


def softmax(z):
    z = z - np.max(z)
    e = np.exp(z)
    return e / e.sum()


class Hist:
    """Geçmiş görünümü: v[:n], t[:n]."""
    __slots__ = ("v", "t", "n")

    def __init__(self, v, t, n):
        self.v = v
        self.t = t
        self.n = n


def token_feat(v):
    x = np.zeros(7)
    x[v] = 1.0
    x[4] = 1.0 if v % 2 == 0 else -1.0
    x[5] = 1.0 if v >= 2 else -1.0
    x[6] = 1.0
    return x


# ---------------------------------------------------------------- özellikler
FEAT_DIM = 36


def features(h, end):
    """end kayıt bilinirken bağlam özellikleri (GB ve Bağlam modeli için)."""
    v, t = h.v, h.t
    f = np.zeros(FEAT_DIM)
    for j in range(5):
        i = end - 1 - j
        if i >= 0:
            f[j * 4 + int(v[i])] = 1.0
    # aralıklar
    for k in range(K):
        g = 30
        for i in range(end - 1, max(-1, end - 31), -1):
            if v[i] == k:
                g = end - 1 - i
                break
        f[20 + k] = min(g, 30) / 10.0
    s = max(0, end - 20)
    if end > s:
        c = np.bincount(np.asarray(v[s:end], dtype=int), minlength=K)
        f[24:28] = c / float(end - s)
    if end >= 1:
        last = v[end - 1]

        def run(fn):
            r = 1
            i = end - 2
            while i >= 0 and fn(v[i]) == fn(last):
                r += 1
                i -= 1
            return min(r, 8) / 4.0
        f[28] = run(lambda x: x)
        f[29] = run(lambda x: x % 2)
        f[30] = run(lambda x: x >= 2)
        hr = ((t[end - 1] // 3600) % 24) / 24.0 * 2 * math.pi
        f[31] = math.sin(hr)
        f[32] = math.cos(hr)
        if end >= 2:
            f[33] = math.log1p(max(0, t[end - 1] - t[end - 2])) / 5.0
    f[34] = min(end, 2000) / 2000.0
    f[35] = 1.0
    return f


# ---------------------------------------------------------------- 1) LSTM
class LSTM:
    id = "lstm"
    name = "LSTM"
    dl = True

    def __init__(self, H=16, T=10, lr=0.05, seed=3):
        rng = np.random.RandomState(seed)
        self.H, self.T, self.lr = H, T, lr
        D = 7 + H
        self.W = rng.uniform(-1, 1, (4 * H, D)) / math.sqrt(D)
        self.b = np.zeros(4 * H)
        self.b[H:2 * H] = 1.0  # unutma kapısı
        self.Wy = rng.uniform(-1, 1, (K, H)) / math.sqrt(H)
        self.by = np.zeros(K)
        self.train = True

    def _fwd(self, h, end):
        H = self.H
        hp = np.zeros(H)
        cp = np.zeros(H)
        cache = []
        for i in range(max(0, end - self.T), end):
            x = np.concatenate([token_feat(int(h.v[i])), hp])
            a = self.W @ x + self.b
            ig = 1 / (1 + np.exp(-a[:H]))
            fg = 1 / (1 + np.exp(-a[H:2 * H]))
            og = 1 / (1 + np.exp(-a[2 * H:3 * H]))
            gg = np.tanh(a[3 * H:])
            c = fg * cp + ig * gg
            tc = np.tanh(c)
            hn = og * tc
            cache.append((x, cp, ig, fg, og, gg, c, tc))
            hp, cp = hn, c
        return hp, cache

    def predict(self, h):
        if h.n < 2:
            return np.full(K, 0.25)
        hT, _ = self._fwd(h, h.n)
        return norm(softmax(self.Wy @ hT + self.by))

    def update(self, h):
        end = h.n - 1
        if end < 2 or not self.train:
            return
        H = self.H
        hT, cache = self._fwd(h, end)
        y = softmax(self.Wy @ hT + self.by)
        dy = y.copy()
        dy[int(h.v[end])] -= 1
        dh = self.Wy.T @ dy
        # Geri alma (undo) anlık görüntüleri dizi nesnelerini paylaştığı için
        # ağırlıklar her zaman YENİ bir diziye yazılır (yerinde -= kullanılmaz).
        self.Wy = self.Wy - self.lr * np.outer(dy, hT)
        self.by = self.by - self.lr * dy
        dW = np.zeros_like(self.W)
        db = np.zeros_like(self.b)
        dc = np.zeros(H)
        for (x, cp, ig, fg, og, gg, c, tc) in reversed(cache):
            do = dh * tc
            dc = dc + dh * og * (1 - tc * tc)
            di = dc * gg
            df = dc * cp
            dg = dc * ig
            da = np.concatenate([di * ig * (1 - ig), df * fg * (1 - fg), do * og * (1 - og), dg * (1 - gg * gg)])
            dW += np.outer(da, x)
            db += da
            dx = self.W.T @ da
            dh = dx[7:]
            dc = dc * fg
        np.clip(dW, -1, 1, out=dW)
        np.clip(db, -1, 1, out=db)
        self.W = self.W - self.lr * dW
        self.b = self.b - self.lr * db


# ---------------------------------------------------------------- 2) Mini Transformer (dikkat)
class MiniTransformer:
    """Tek başlı dikkat (induction head): şu anki bağlama benzeyen geçmiş konumlara dikkat eder,
    onların ardından gelen değerleri oylar. Bilineer dikkat matrisi M ve konum yanlılığı öğrenilir."""
    id = "transformer"
    name = "Transformer"
    dl = True

    def __init__(self, C=96, lr=0.05, seed=5):
        rng = np.random.RandomState(seed)
        self.C, self.lr = C, lr
        self.D = 13
        self.M = np.eye(self.D) * 0.5 + rng.normal(0, 0.05, (self.D, self.D))
        self.pb = np.zeros(C)
        self.train = True
        self._cache = None

    def _tok(self, v, i):
        x = np.zeros(self.D)
        for j in range(3):
            if i - j >= 0:
                x[j * 4 + int(v[i - j])] = 1.0
        x[12] = 1.0
        return x

    def _attn(self, h, end):
        if end < 3:
            return None
        q = self._tok(h.v, end - 1)
        lo = max(0, end - 1 - self.C)
        idx = np.arange(lo, end - 1)
        if len(idx) == 0:
            return None
        Kx = np.stack([self._tok(h.v, i) for i in idx])
        dist = (end - 2) - idx
        s = Kx @ (self.M.T @ q) + self.pb[dist]
        a = softmax(s)
        Y = np.zeros((len(idx), K))
        Y[np.arange(len(idx)), np.asarray(h.v[idx + 1], dtype=int)] = 1.0
        p = a @ Y
        return q, Kx, dist, a, Y, p

    def predict(self, h):
        r = self._attn(h, h.n)
        if r is None:
            return np.full(K, 0.25)
        return norm(0.85 * r[5] + 0.15 / K)

    def update(self, h):
        if not self.train:
            return
        end = h.n - 1
        r = self._attn(h, end)
        if r is None:
            return
        q, Kx, dist, a, Y, p = r
        y = int(h.v[end])
        pa = 0.85 * p[y] + 0.15 / K
        ds = -(0.85 / pa) * a * (Y[:, y] - p[y])
        gM = np.outer(q, ds @ Kx)  # dL/dM = sum_j ds_j q k_j^T
        self.M = self.M - self.lr * np.clip(gM, -1, 1)
        upd = np.zeros(self.C)          # yerinde np.add.at yerine: yeni dizi (undo için güvenli)
        np.add.at(upd, np.asarray(dist, dtype=int), -self.lr * ds)
        self.pb = self.pb + upd


# ---------------------------------------------------------------- 3) 1D-CNN
class CNN1D:
    id = "cnn"
    name = "1D-CNN"
    dl = True

    def __init__(self, W=16, F=8, lr=0.03, seed=9):
        rng = np.random.RandomState(seed)
        self.Wn, self.F, self.lr = W, F, lr
        self.C = 6
        self.k = rng.normal(0, 0.3, (F, self.C, 3))
        self.kb = np.zeros(F)
        self.Wo = rng.normal(0, 0.1, (K, 2 * F + 1))
        self.train = True

    def _x(self, h, end):
        X = np.zeros((self.C, self.Wn))
        for j in range(self.Wn):
            i = end - self.Wn + j
            if i >= 0:
                v = int(h.v[i])
                X[v, j] = 1.0
                X[4, j] = 1.0 if v % 2 == 0 else -1.0
                X[5, j] = 1.0 if v >= 2 else -1.0
        return X

    def _fwd(self, X):
        L = self.Wn - 2
        Z = np.zeros((self.F, L))
        for p in range(L):
            Z[:, p] = np.einsum("fcw,cw->f", self.k, X[:, p:p + 3]) + self.kb
        A = np.maximum(Z, 0)
        mi = A.argmax(axis=1)
        feat = np.concatenate([A[np.arange(self.F), mi], A[:, -1], [1.0]])
        return Z, A, mi, feat

    def predict(self, h):
        if h.n < 4:
            return np.full(K, 0.25)
        _, _, _, f = self._fwd(self._x(h, h.n))
        return norm(softmax(self.Wo @ f))

    def update(self, h):
        end = h.n - 1
        if end < 4 or not self.train:
            return
        X = self._x(h, end)
        Z, A, mi, f = self._fwd(X)
        y = softmax(self.Wo @ f)
        dy = y.copy()
        dy[int(h.v[end])] -= 1
        df = self.Wo.T @ dy
        self.Wo = self.Wo - self.lr * np.outer(dy, f)
        L = self.Wn - 2
        k = None
        kb = None
        for fi in range(self.F):
            for pos, g in ((mi[fi], df[fi]), (L - 1, df[self.F + fi])):
                if Z[fi, pos] > 0 and g != 0:
                    if k is None:                 # kopya üzerinde çalış: eski dizi undo için bozulmadan kalır
                        k = self.k.copy()
                        kb = self.kb.copy()
                    k[fi] -= self.lr * g * X[:, pos:pos + 3]
                    kb[fi] -= self.lr * g
        if k is not None:
            self.k = k
            self.kb = kb


# ---------------------------------------------------------------- 4) Kalıp 2.0 bulanık
class KalipBulanik:
    """Tüm geçmişte en fazla 1 farkla eşleşen kalıpları (ve aynalarını) arar."""
    id = "kalip_fuzzy"
    name = "Kalıp 2.0 bulanık"
    dl = False

    def __init__(self, lens=range(4, 13)):
        self.lens = list(lens)

    def predict(self, h):
        n = h.n
        if n < 6:
            return np.full(K, 0.25)
        v = np.asarray(h.v[:n], dtype=np.int8)
        votes = np.zeros(K)
        for L in self.lens:
            if n <= L + 1:
                break
            s = v[n - L:]
            W = np.lib.stride_tricks.sliding_window_view(v[:n - 1], L)  # son eleman takipçi olmalı
            fol = v[L:n]
            m = W.shape[0]
            W = W[:m]
            fol = fol[:m]
            tol = 1 if L >= 6 else 0
            mis = (W != s).sum(axis=1)
            ok = mis <= tol
            if ok.any():
                w = (L ** 1.5) * np.where(mis[ok] == 0, 1.0, 0.4)
                np.add.at(votes, fol[ok], w)
            mir = (W != (3 - s)).sum(axis=1)
            ok2 = mir <= tol
            if ok2.any():
                w2 = 0.5 * (L ** 1.5) * np.where(mir[ok2] == 0, 1.0, 0.4)
                np.add.at(votes, 3 - fol[ok2], w2)
        tot = votes.sum()
        if tot <= 0:
            return np.full(K, 0.25)
        return norm((votes + 0.25 * tot * 0.2) / (tot * 1.2))

    def update(self, h):
        pass


# ---------------------------------------------------------------- 5) kNN-DTW
class KnnDtw:
    id = "knn_dtw"
    name = "kNN-DTW"
    dl = False

    def __init__(self, w=8, k=25):
        self.w, self.k = w, k

    def predict(self, h):
        n, w = h.n, self.w
        if n < w + 10:
            return np.full(K, 0.25)
        v = np.asarray(h.v[:n], dtype=np.int8)
        q = v[n - w:]
        W = np.lib.stride_tricks.sliding_window_view(v[:n - 1], w)
        fol = v[w:n]
        m = min(W.shape[0], len(fol))
        W, fol = W[:m], fol[:m]

        def cost(a, b):
            return (a != b) * 1.0 + ((a >= 2) != (b >= 2)) * 0.5 + ((a % 2) != (b % 2)) * 0.5

        # Bantlı DTW (|i-j|<=1), tüm pencereler için vektörel
        INF = 1e9
        D = np.full((m, w + 1, w + 1), INF)
        D[:, 0, 0] = 0
        for i in range(1, w + 1):
            for j in range(max(1, i - 1), min(w, i + 1) + 1):
                c = cost(W[:, i - 1], q[j - 1])
                D[:, i, j] = c + np.minimum(np.minimum(D[:, i - 1, j], D[:, i, j - 1]), D[:, i - 1, j - 1])
        d = D[:, w, w]
        kk = min(self.k, m)
        idx = np.argpartition(d, kk - 1)[:kk]
        wt = np.exp(-d[idx])
        votes = np.zeros(K)
        np.add.at(votes, fol[idx], wt)
        return norm((votes + 0.3) / (votes.sum() + 1.2))

    def update(self, h):
        pass


# ---------------------------------------------------------------- 6) Spektral + BOCPD
class SpectralBocpd:
    id = "spectral"
    name = "Spektral/BOCPD"
    dl = False

    def __init__(self, R=200, hazard=1.0 / 150):
        self.R, self.hz = R, hazard
        self.rp = np.array([1.0])
        self.cnt = np.zeros((1, K))
        self.spec = None
        self.step = 0

    def _bocpd_pred(self):
        pr = (self.cnt + 0.5) / (self.cnt.sum(axis=1, keepdims=True) + 2.0)
        return self.rp @ pr

    def _spectral(self, h):
        n = h.n
        L = min(n, 256)
        if L < 64:
            self.spec = None
            return
        v = np.asarray(h.v[n - L:n], dtype=int)
        comps = []
        for k in range(K):
            x = (v == k).astype(float) - 0.25
            F = np.fft.rfft(x)
            P = np.abs(F[1:]) ** 2
            top = np.argsort(P)[-3:] + 1
            comps.append([(f, F[f]) for f in top])
        self.spec = (L, n, comps)

    def _spec_pred(self, h):
        if self.spec is None:
            return np.full(K, 0.25)
        L, n0, comps = self.spec
        t = (h.n - n0) + L  # tahmin edilen konum
        sc = np.zeros(K)
        for k in range(K):
            for f, c in comps[k]:
                sc[k] += 2.0 / L * (c.real * math.cos(2 * math.pi * f * t / L) - c.imag * math.sin(2 * math.pi * f * t / L))
        return softmax(sc * 4.0)

    def predict(self, h):
        return norm(0.6 * self._bocpd_pred() + 0.4 * self._spec_pred(h))

    def update(self, h):
        x = int(h.v[h.n - 1])
        pr = (self.cnt[:, x] + 0.5) / (self.cnt.sum(axis=1) + 2.0)
        grow = self.rp * pr * (1 - self.hz)
        cp = (self.rp * pr * self.hz).sum()
        rp = np.concatenate([[cp], grow])[: self.R]
        rp /= rp.sum()
        cnt = np.vstack([np.zeros((1, K)), self.cnt])[: self.R]
        cnt[1:, x] += 1
        self.rp, self.cnt = rp, cnt
        self.step += 1
        if self.step % 10 == 0:
            self._spectral(h)


# ---------------------------------------------------------------- 7) Gradient Boosting
class GradBoost:
    """Çok sınıflı gradient boosting (karar kütükleri); periyodik yeniden eğitim."""
    id = "gboost"
    name = "Gradient Boost"
    dl = False

    def __init__(self, every=50, rounds=25, lr=0.3, maxn=600):
        self.every, self.rounds, self.lr, self.maxn = every, rounds, lr, maxn
        self.X = []
        self.y = []
        self.model = None
        self.since = 0
        self.slow = 1

    def _fit(self):
        X = np.array(self.X[-self.maxn:])
        y = np.array(self.y[-self.maxn:])
        n = len(y)
        if n < 80:
            self.model = None
            return
        Y = np.zeros((n, K))
        Y[np.arange(n), y] = 1
        F = np.zeros((n, K))
        stumps = []
        # eşik adayları: her özellik için 3 çeyreklik
        qs = np.percentile(X, [25, 50, 75], axis=0)
        for _ in range(self.rounds):
            Pm = np.exp(F - F.max(axis=1, keepdims=True))
            Pm /= Pm.sum(axis=1, keepdims=True)
            G = Y - Pm
            rnd = []
            for k in range(K):
                g = G[:, k]
                best = (0, 0, 0.0, 0.0, 0.0)
                bestgain = -1
                for qi in range(3):
                    thr = qs[qi]
                    mask = X > thr  # n × d
                    cnt_r = mask.sum(axis=0)
                    cnt_l = n - cnt_r
                    s_r = g @ mask
                    s_l = g.sum() - s_r
                    gain = np.where(cnt_r > 5, s_r ** 2 / np.maximum(cnt_r, 1), 0) + np.where(cnt_l > 5, s_l ** 2 / np.maximum(cnt_l, 1), 0)
                    j = int(np.argmax(gain))
                    if gain[j] > bestgain:
                        bestgain = gain[j]
                        vl = s_l[j] / max(cnt_l[j], 1)
                        vr = s_r[j] / max(cnt_r[j], 1)
                        best = (j, qi, thr[j], vl * 1.5, vr * 1.5)
                j, _, thr, vl, vr = best
                F[:, k] += self.lr * np.where(X[:, j] > thr, vr, vl)
                rnd.append((j, thr, vl, vr))
            stumps.append(rnd)
        self.model = stumps

    def predict(self, h):
        if self.model is None:
            return np.full(K, 0.25)
        x = features(h, h.n)
        f = np.zeros(K)
        for rnd in self.model:
            for k, (j, thr, vl, vr) in enumerate(rnd):
                f[k] += self.lr * (vr if x[j] > thr else vl)
        return norm(softmax(f))

    def update(self, h):
        end = h.n - 1
        self.X.append(features(h, end))
        self.y.append(int(h.v[end]))
        if len(self.X) > 3000:
            self.X = self.X[-self.maxn:]
            self.y = self.y[-self.maxn:]
        self.since += 1
        if self.since >= self.every * self.slow:
            self.since = 0
            self._fit()

    # ---- geri alma: örneklem listelerini kopyalamadan, kimlik + uzunlukla O(1) ----
    def snap(self):
        return (self.X, len(self.X), self.y, len(self.y), self.since, self.model)

    def can_restore(self, s):
        return True

    def restore(self, s):
        X, nx, Y, ny, since, model = s
        if self.X is X:
            del X[nx:]
        else:
            self.X = X[:nx]
        if self.y is Y:
            del Y[ny:]
        else:
            self.y = Y[:ny]
        self.since = since
        self.model = model
        return True


# ---------------------------------------------------------------- 8) Bağlam modeli
class ContextModel:
    """Çevrimiçi softmax regresyon: son sayılar, aralıklar, seri uzunlukları, saat ve giriş aralığı."""
    id = "context"
    name = "Bağlam modeli"
    dl = False

    def __init__(self, lr=0.02, l2=1e-4):
        self.W = np.zeros((K, FEAT_DIM))
        self.lr, self.l2 = lr, l2

    def predict(self, h):
        return norm(softmax(self.W @ features(h, h.n)))

    def update(self, h):
        end = h.n - 1
        x = features(h, end)
        p = softmax(self.W @ x)
        p[int(h.v[end])] -= 1
        self.W = self.W - self.lr * (np.outer(p, x) + self.l2 * self.W)


# ---------------------------------------------------------------- 9) Motif keşfi
class MotifDiscovery:
    """Anlamlı (yeterli desteği olan, şanstan sapan) motifleri bulur ve 'lift' ile oylar."""
    id = "motif"
    name = "Motif keşfi"
    dl = False

    def __init__(self, kmax=6, support=8):
        self.kmax, self.support = kmax, support
        self.tab = {}
        self._undo = []      # [(anahtar, eski dizi ya da None)] — son adımların değişiklik günlüğü
        self._dropped = 0    # günlük kırpıldıysa atılan kayıt sayısı

    def predict(self, h):
        n = h.n
        v = h.v
        logp = np.zeros(K)
        used = 0
        for k in range(2, self.kmax + 1):
            if n < k:
                break
            key = (k,) + tuple(int(x) for x in v[n - k:n])
            c = self.tab.get(key)
            if c is None:
                continue
            tot = c.sum()
            if tot < self.support:
                continue
            p = (c + 0.5) / (tot + 2.0)
            # z-skoru: şanstan sapma yeterince büyükse kullan
            z = np.abs(p - 0.25) / math.sqrt(0.1875 / tot)
            if z.max() < 1.5:
                continue
            logp += min(1.0, tot / 30.0) * np.log(p / 0.25)
            used += 1
        if used == 0:
            return np.full(K, 0.25)
        return norm(softmax(logp))

    def update(self, h):
        n = h.n
        v = h.v
        a = int(v[n - 1])
        for k in range(2, self.kmax + 1):
            if n - 1 < k:
                break
            key = (k,) + tuple(int(x) for x in v[n - 1 - k:n - 1])
            old = self.tab.get(key)
            c = np.zeros(K) if old is None else old.copy()   # kopya üzerinde yaz: eski durum bozulmaz
            c[a] += 1
            self.tab[key] = c
            self._undo.append((key, old))
        if len(self._undo) > UNDO_LOG_MAX:
            keep = UNDO_LOG_MAX // 2
            self._dropped += len(self._undo) - keep
            del self._undo[:len(self._undo) - keep]

    # ---- geri alma: tablo kopyalanmaz, günlük geri sarılır ----
    def snap(self):
        return (self.tab, len(self._undo) + self._dropped)

    def can_restore(self, s):
        return s[1] >= self._dropped

    def restore(self, s):
        tab, depth = s
        target = depth - self._dropped
        if target < 0:
            return False
        self.tab = tab
        while len(self._undo) > target:
            key, old = self._undo.pop()
            if old is None:
                tab.pop(key, None)
            else:
                tab[key] = old
        return True


# ---------------------------------------------------------------- 10) HMM
class HMM:
    id = "hmm"
    name = "HMM"
    dl = False

    def __init__(self, S=3, every=50, win=400, seed=1):
        rng = np.random.RandomState(seed)
        self.S, self.every, self.win = S, every, win
        self.A = np.full((S, S), 0.1 / (S - 1))
        np.fill_diagonal(self.A, 0.9)
        self.B = rng.dirichlet(np.ones(K) * 5, S)
        self.pi = np.full(S, 1.0 / S)
        self.alpha = self.pi.copy()
        self.since = 0
        self.slow = 1

    def _fit(self, obs):
        S = self.S
        A, B, pi = self.A.copy(), self.B.copy(), self.pi.copy()
        T = len(obs)
        for _ in range(8):
            al = np.zeros((T, S))
            c = np.zeros(T)
            al[0] = pi * B[:, obs[0]]
            c[0] = al[0].sum()
            al[0] /= c[0]
            for t in range(1, T):
                al[t] = (al[t - 1] @ A) * B[:, obs[t]]
                c[t] = al[t].sum()
                al[t] /= c[t]
            be = np.ones((T, S))
            for t in range(T - 2, -1, -1):
                be[t] = (A @ (B[:, obs[t + 1]] * be[t + 1])) / c[t + 1]
            g = al * be
            g /= g.sum(axis=1, keepdims=True)
            xi = np.zeros((S, S))
            for t in range(T - 1):
                m = np.outer(al[t], B[:, obs[t + 1]] * be[t + 1]) * A / c[t + 1]
                xi += m
            A = xi / xi.sum(axis=1, keepdims=True)
            for k in range(K):
                B[:, k] = g[obs == k].sum(axis=0) + 0.5
            B /= B.sum(axis=1, keepdims=True)
            pi = g[0]
        self.A, self.B, self.pi = A, B, pi
        # filtrelemeyi yeniden hesapla
        a = pi * B[:, obs[0]]
        a /= a.sum()
        for t in range(1, T):
            a = (a @ A) * B[:, obs[t]]
            a /= a.sum()
        self.alpha = a

    def predict(self, h):
        return norm((self.alpha @ self.A) @ self.B)

    def update(self, h):
        x = int(h.v[h.n - 1])
        a = (self.alpha @ self.A) * self.B[:, x]
        self.alpha = a / a.sum()
        self.since += 1
        if self.since >= self.every * self.slow and h.n >= 100:
            self.since = 0
            self._fit(np.asarray(h.v[max(0, h.n - self.win):h.n], dtype=int))


def all_members():
    return [LSTM(), MiniTransformer(), CNN1D(), KalipBulanik(), KnnDtw(),
            SpectralBocpd(), GradBoost(), ContextModel(), MotifDiscovery(), HMM()]
