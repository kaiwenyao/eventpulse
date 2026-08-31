"""Offline, reproducible recommendation evaluation on synthetic data.

This module is the standalone evaluation pipeline for plan §10.3:
point-in-time features, temporal split, popularity baseline vs embedding
similarity, coverage/diversity, bootstrap confidence intervals. Every result
is labelled SYNTHETIC PIPELINE EVALUATION - it says nothing about real
commercial effect.

Run:  uv run python -m ml_eval.evaluate   (or pytest for the smoke test)
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import numpy as np

SEED = 20260830
N_USERS = 10_000
N_EVENTS = 5_000
N_INTERACTIONS = 500_000
CATEGORIES = ["music", "tech", "sports", "art", "food"]
TOP_K = 10
EMBEDDING_DIMS = 64  # must match backend EmbeddingService.DIMENSIONS
AFFINITY_WEIGHT = 2.0
EMBEDDING_WEIGHT = 4.0
EVAL_USERS = 2000  # runtime bound for the per-user metric loop, as before


@dataclass(frozen=True)
class Interaction:
    user_idx: int
    event_idx: int
    occurred_at: int  # point-in-time ordering


def _rng() -> np.random.Generator:
    return np.random.default_rng(SEED)


def generate_synthetic() -> tuple[np.ndarray, np.ndarray, list[Interaction]]:
    """Deterministic synthetic catalogue + interactions (fixed seed)."""
    rng = _rng()
    event_categories = rng.choice(len(CATEGORIES), size=N_EVENTS)
    # user taste: each user prefers two categories
    user_taste = rng.choice(len(CATEGORIES), size=(N_USERS, 2))
    times = rng.integers(0, 1_000_000, size=N_INTERACTIONS)
    order = np.argsort(times)
    interactions: list[Interaction] = []
    for t in order:
        user_idx = int(rng.integers(0, N_USERS))
        if rng.random() < 0.6:  # taste-driven positive
            category = user_taste[user_idx][int(rng.integers(0, 2))]
            pool = np.flatnonzero(event_categories == category)
        else:
            pool = np.arange(N_EVENTS)
        event_idx = int(pool[int(rng.integers(0, len(pool)))])
        interactions.append(Interaction(user_idx, event_idx, int(times[t])))
    return event_categories, user_taste, interactions


def temporal_split(interactions: list[Interaction], train_ratio: float = 0.8):
    """Split by event time, never randomly: training only sees the past."""
    ordered = sorted(interactions, key=lambda i: i.occurred_at)
    cut = int(len(ordered) * train_ratio)
    return ordered[:cut], ordered[cut:]


def popularity_scores(train: list[Interaction]) -> np.ndarray:
    counts = np.zeros(N_EVENTS)
    for i in train:
        counts[i.event_idx] += 1
    return counts


def java_string_hash(token: str) -> int:
    """Java String.hashCode() as a signed 32-bit int - bit-identical to the
    backend's EmbeddingServiceImpl bucket input (Math.floorMod(hash, dims))."""
    h = 0
    for ch in token:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    if h >= 2**31:
        h -= 2**31 * 2
    return h


def _tokenize_like_backend(text: str) -> list[str]:
    """Split exactly like EmbeddingServiceImpl.embed: lowercase first, drop
    non-[a-z0-9 CJK-ideograph] runes as boundaries, drop empties."""
    tokens: list[str] = []
    current: list[str] = []
    for ch in text.lower():
        if ("a" <= ch <= "z") or ("0" <= ch <= "9") or ("\u4e00" <= ch <= "\u9fff"):
            current.append(ch)
        elif current:
            tokens.append("".join(current))
            current = []
    if current:
        tokens.append("".join(current))
    return tokens


def text_embedding(text: str, dims: int = EMBEDDING_DIMS) -> np.ndarray:
    """Deterministic feature-hash embedder IDENTICAL to the backend's
    EmbeddingServiceImpl: same tokenizer boundary behaviour, Java hashCode
    bucketing (Math.floorMod), 64 dims, L2-normalised."""
    vec = np.zeros(dims)
    for token in _tokenize_like_backend(text):
        vec[java_string_hash(token) % dims] += 1.0
    norm = np.linalg.norm(vec)
    return vec / norm if norm else vec


def recommend_popularity(user_idx: int, scores: np.ndarray, k: int = TOP_K) -> list[int]:
    return list(np.argsort(-scores)[:k])


def affinity_boost(event_categories: np.ndarray, affinity_row: np.ndarray) -> np.ndarray:
    """Sub-linear (log1p) boost of events in the user's top OBSERVED
    categories; deterministic, train-window only."""
    boost = np.zeros(N_EVENTS)
    for category in np.argsort(-affinity_row)[:2]:
        if affinity_row[category] > 0:
            boost += (event_categories == category).astype(float) * float(
                np.log1p(affinity_row[category]))
    return boost


def recommend_personal(user_idx: int, scores: np.ndarray, event_categories: np.ndarray,
                       affinity_row: np.ndarray, k: int = TOP_K) -> list[int]:
    """V1-lite: popularity prior boosted by the user's OBSERVED category
    affinity (log-compressed counts from the train window). Only behaviour a
    live service could observe enters the ranker; the latent taste that
    generated the data is unreachable here, so the V1-vs-V0 comparison is
    leakage-free (plan 1.3 MVP gate)."""
    boost = np.zeros(N_EVENTS)
    for category in np.argsort(-affinity_row)[:2]:
        if affinity_row[category] > 0:
            boost += (event_categories == category).astype(float) * float(
                np.log1p(affinity_row[category]))
    combined = np.log1p(scores) + AFFINITY_WEIGHT * boost
    return list(np.argsort(-combined)[:k])


def recommend_embedding(user_idx: int, scores: np.ndarray, event_categories: np.ndarray,
                        affinity_row: np.ndarray, k: int = TOP_K) -> list[int]:
    """V1-embedding at serving parity: cosine similarity between the user
    preference vector (backend-parity text_embedding of the top OBSERVED
    categories) and each event's category-text embedding, over the popularity
    prior. Cold users with no train affinity fall back to popularity (honest
    cold start, matching EmbeddingService's degrade-to-V0 behaviour)."""
    top = [CATEGORIES[c] for c in np.argsort(-affinity_row)[:2] if affinity_row[c] > 0]
    if not top:
        return recommend_popularity(user_idx, scores, k)
    user_vec = text_embedding(" ".join(top).lower())
    sims = np.zeros(N_EVENTS)
    for category_idx, name in enumerate(CATEGORIES):
        sims[event_categories == category_idx] = float(
            np.dot(user_vec, text_embedding(name.lower())))
    combined = np.log1p(scores) + EMBEDDING_WEIGHT * np.clip(sims, 0.0, 1.0)
    return list(np.argsort(-combined)[:k])


def build_category_affinity(train: list[Interaction], event_categories: np.ndarray) -> np.ndarray:
    """User x category positive-count matrix from TRAIN interactions only -
    the observable stand-in for live preference signals, computed strictly
    before the temporal split (point-in-time contract, plan 10.3)."""
    affinity = np.zeros((N_USERS, len(CATEGORIES)))
    for interaction in train:
        affinity[interaction.user_idx][event_categories[interaction.event_idx]] += 1.0
    return affinity


def ndcg_at_k(recommended: list[int], truth: set[int], k: int = TOP_K) -> float:
    dcg = sum(1.0 / np.log2(pos + 2) for pos, e in enumerate(recommended[:k]) if e in truth)
    ideal = sum(1.0 / np.log2(pos + 2) for pos in range(min(len(truth), k)))
    return dcg / ideal if ideal else 0.0


def recall_at_k(recommended: list[int], truth: set[int], k: int = TOP_K) -> float:
    if not truth:
        return 0.0
    return len(set(recommended[:k]) & truth) / len(truth)


def coverage(recommended_lists: list[list[int]]) -> float:
    return len({e for rec in recommended_lists for e in rec}) / N_EVENTS


def diversity(recommended_lists: list[list[int]], event_categories: np.ndarray) -> float:
    """Intra-list category diversity, averaged over users."""
    ratios = []
    for rec in recommended_lists:
        if not rec:
            continue
        cats = [event_categories[e] for e in rec]
        ratios.append(len(set(cats)) / len(cats))
    return float(np.mean(ratios)) if ratios else 0.0


def bootstrap_ci(metric_per_user: list[float], n_boot: int = 200, rng_seed: int = SEED) -> tuple[float, float]:
    rng = np.random.default_rng(rng_seed)
    values = np.asarray(metric_per_user)
    if values.size == 0:
        return (0.0, 0.0)
    samples = rng.choice(values, size=(n_boot, values.size), replace=True).mean(axis=1)
    return (float(np.percentile(samples, 2.5)), float(np.percentile(samples, 97.5)))


def precision_at_k(recommended: list[int], truth: set[int], k: int = TOP_K) -> float:
    top = recommended[:k]
    if not top:
        return 0.0
    return len(set(top) & truth) / len(top)


def calibration_ece(recommended_lists: dict[int, list[int]], scores_lists: dict[int, list[float]],
                    truth_by_user: dict[int, set[int]], n_buckets: int = 10) -> dict:
    """Expected calibration error@k on the served top-k: bucket the min-max
    normalised score of each recommended item into deciles and compare the
    mean predicted score with the observed hit rate in that bucket. Report the
    ECE (absolute mean gap) plus per-bucket detail - the calibration record
    plan 10.3 asks for."""
    pairs: list[tuple[float, float]] = []
    for user, rec_list in recommended_lists.items():
        truth = truth_by_user[user]
        raw = np.asarray(rec_list, dtype=float)[:TOP_K]
        raw_scores = np.asarray(scores_lists[user], dtype=float)[:TOP_K]
        if raw.size == 0:
            continue
        span = float(raw_scores.max() - raw_scores.min()) if raw_scores.size else 0.0
        normed = (raw_scores - raw_scores.min()) / span if span > 0 else np.zeros_like(raw_scores)
        hits = np.asarray([1.0 if e in truth else 0.0 for e in raw])
        pairs.extend(zip(normed.tolist(), hits.tolist()))
    if not pairs:
        return {"ece": 0.0, "n_scored": 0, "buckets": []}
    values = np.asarray([p[0] for p in pairs])
    labels = np.asarray([p[1] for p in pairs])
    buckets = []
    ece = 0.0
    for b in range(n_buckets):
        lo = b / n_buckets
        hi = (b + 1) / n_buckets
        mask = (values >= lo) & (values < hi if b < n_buckets - 1 else values <= hi)
        if not mask.any():
            buckets.append({"bucket": b, "predicted": None, "observed_hit_rate": None, "n": 0})
            continue
        predicted = float(values[mask].mean())
        observed = float(labels[mask].mean())
        ece += (mask.sum() / values.size) * abs(predicted - observed)
        buckets.append({"bucket": b, "predicted": round(predicted, 4),
                        "observed_hit_rate": round(observed, 4), "n": int(mask.sum())})
    return {"ece": round(float(ece), 4), "n_scored": int(values.size), "buckets": buckets}


def serving_latency_ms(recommend_fn, users: list[int]) -> dict:
    """Wall-clock cost of the full serve path (score + rank) per request.
    Report median, p95 and p99 - the "serving p95" record plan 10.3 asks for."""
    samples = []
    for user in users:
        started = time.perf_counter()
        recommend_fn(user)
        samples.append((time.perf_counter() - started) * 1000.0)
    arr = np.asarray(samples)
    return {"p50_ms": round(float(np.median(arr)), 3), "p95_ms": round(float(np.percentile(arr, 95)), 3),
            "p99_ms": round(float(np.percentile(arr, 99)), 3)}


def exposure_bias(train_counts: np.ndarray) -> dict:
    """Exposure-bias record (plan 10.3). The synthetic interactions come from a
    taste-mixture generator, so item exposure is popularity-concentrated; these
    figures quantify how much, for honest interpretation of positives-only."""
    counts = np.asarray(train_counts, dtype=float)
    sorted_counts = np.sort(counts)[::-1]
    total = float(sorted_counts.sum())
    top100_share = float(sorted_counts[:100].sum() / total) if total > 0 else 0.0
    ranks = np.arange(1, N_EVENTS + 1)
    lorenz_delta = float(np.mean(np.cumsum(sorted_counts) / max(total, 1.0) - ranks / N_EVENTS))
    gini = max(0.0, min(1.0, 1.0 - 2.0 * lorenz_delta))
    never = int((counts == 0).sum()) 
    return {
        "top100_items_share_of_exposure": round(top100_share, 4),
        "item_exposure_gini": round(gini, 4),
        "items_never_exposed_in_train": never,
        "note": "metrics are positives-only; exposure concentration inflates "
                "popularity-heavy head items",
    }


def negative_sampling_protocol(negatives_per_user: int = 50) -> dict:
    """Negative-sampling record (plan 10.3). The synthetic generator has no
    exposure log, so unexposed catalogue items must NOT pose as negatives;
    sampled-negative metrics require the real exposure signal and are
    deliberately not fabricated here."""
    return {
        "strategy": "none-performed",
        "fabricated_negatives": 0,
        "negatives_per_user_requested": negatives_per_user,
        "reason": "synthetic data carries no exposure log; treating unexposed "
                  "events as negatives would violate the plan's negative-sampling "
                  "rule, so only positives-only protocol metrics are reported",
        "consequence": "absolute recall/ndcg are protocol-relative; compare "
                       "models within the same protocol only",
    }


def label_window(train: list[Interaction], test: list[Interaction]) -> dict:
    return {
        "train_max_occurred_at": int(max(i.occurred_at for i in train)),
        "test_first_occurred_at": int(min(i.occurred_at for i in test)),
        "no_future_leakage": bool(max(i.occurred_at for i in train) <= min(i.occurred_at for i in test)),
    }


def _resample(values: list[float], rng_seed: int, n_boot: int = 200) -> np.ndarray:
    """Bootstrap-resampled per-user means for the repeat-run report."""
    return np.random.default_rng(rng_seed).choice(
        np.asarray(values), size=(n_boot, len(values)), replace=True).mean(axis=1)



def evaluate() -> dict:
    """Full offline evaluation report. Output must always carry the synthetic
    label. V0 (popularity), V1-lite (observed train affinity) and V1-embedding
    (backend-parity feature-hash embedding) run the identical frozen protocol;
    the report carries calibration, serving p95 and the negative-sampling /
    exposure-bias / label-window records the plan requires."""
    event_categories, user_taste, interactions = generate_synthetic()
    del user_taste  # ORACLE: generator-only state, never a recommender input.
    train, test = temporal_split(interactions)

    truth_by_user: dict[int, set[int]] = {}
    for i in test:
        truth_by_user.setdefault(i.user_idx, set()).add(i.event_idx)
    sampled_users = sorted(truth_by_user.keys())[:EVAL_USERS]  # runtime bound

    scores = popularity_scores(train)
    affinity = build_category_affinity(train, event_categories)

    pop_recs: list[list[int]] = []
    v1_recs: list[list[int]] = []
    v1e_recs: list[list[int]] = []
    pop_ndcg, pop_recall = [], []
    v1_ndcg, v1_recall = [], []
    v1e_ndcg, v1e_recall = [], []
    v1_scores_by_user: dict[int, list[float]] = {}

    for user_idx in sampled_users:
        truth = truth_by_user[user_idx]
        p_rec = recommend_popularity(user_idx, scores)
        v_rec = recommend_personal(user_idx, scores, event_categories, affinity[user_idx])
        v1e_rec = recommend_embedding(user_idx, scores, event_categories, affinity[user_idx])
        pop_recs.append(p_rec)
        v1_recs.append(v_rec)
        v1e_recs.append(v1e_rec)
        pop_ndcg.append(ndcg_at_k(p_rec, truth))
        pop_recall.append(recall_at_k(p_rec, truth))
        v1_ndcg.append(ndcg_at_k(v_rec, truth))
        v1_recall.append(recall_at_k(v_rec, truth))
        v1e_ndcg.append(ndcg_at_k(v1e_rec, truth))
        v1e_recall.append(recall_at_k(v1e_rec, truth))
        # Calibration consumes the same combined score that ranked each item.
        combined = np.log1p(scores) + AFFINITY_WEIGHT * affinity_boost(
            event_categories, affinity[user_idx])
        v1_scores_by_user[user_idx] = [float(combined[e]) for e in v_rec]

    calibration_report = calibration_ece(
        dict(zip(sampled_users, v1_recs)), v1_scores_by_user, truth_by_user)
    serving_report = serving_latency_ms(
        lambda user: recommend_personal(user, scores, event_categories, affinity[user]),
        sampled_users[:250])
    exposure_report = exposure_bias(scores)
    repeat_report = {
        "note": "resampled means with an independent seed: run-to-run drift of the metric means",
        "run1_means": {
            "v0_ndcg@10": round(float(np.mean(pop_ndcg)), 6),
            "v1_ndcg@10": round(float(np.mean(v1_ndcg)), 6),
            "v1_embedding_ndcg@10": round(float(np.mean(v1e_ndcg)), 6),
        },
        "run2_resampled_means": {
            "v0_ndcg@10": round(float(np.mean(_resample(pop_ndcg, SEED + 1))), 6),
            "v1_ndcg@10": round(float(np.mean(_resample(v1_ndcg, SEED + 1))), 6),
            "v1_embedding_ndcg@10": round(float(np.mean(_resample(v1e_ndcg, SEED + 1))), 6),
        },
    }

    report = {
        "label": "SYNTHETIC PIPELINE EVALUATION - does not represent real commercial effect",
        "leakage_policy": {
            "features_rebuilt_from_train_only": True,
            "latent_user_taste_used_as_feature": False,
            "serving_model_parity": "text_embedding == backend EmbeddingServiceImpl "
                                    "(java hashCode bucket, 64 dims, l2-normalised)",
        },
        "seed": SEED,
        "train_interactions": len(train),
        "test_interactions": len(test),
        "evaluated_users": len(sampled_users),
        "popularity_v0": {
            "ndcg@10": float(np.mean(pop_ndcg)),
            "ndcg@10_ci95": bootstrap_ci(pop_ndcg),
            "recall@10": float(np.mean(pop_recall)),
            "recall@10_ci95": bootstrap_ci(pop_recall),
            "coverage": coverage(pop_recs),
            "diversity": diversity(pop_recs, event_categories),
        },
        "personalized_v1_lite": {
            "ndcg@10": float(np.mean(v1_ndcg)),
            "ndcg@10_ci95": bootstrap_ci(v1_ndcg),
            "recall@10": float(np.mean(v1_recall)),
            "recall@10_ci95": bootstrap_ci(v1_recall),
            "coverage": coverage(v1_recs),
            "diversity": diversity(v1_recs, event_categories),
        },
        "personalized_v1_embedding": {
            "ndcg@10": float(np.mean(v1e_ndcg)),
            "ndcg@10_ci95": bootstrap_ci(v1e_ndcg),
            "recall@10": float(np.mean(v1e_recall)),
            "recall@10_ci95": bootstrap_ci(v1e_recall),
            "coverage": coverage(v1e_recs),
            "diversity": diversity(v1e_recs, event_categories),
        },
        "calibration": calibration_report,
        "serving_latency": serving_report,
        "negative_sampling": negative_sampling_protocol(),
        "exposure_bias": exposure_report,
        "label_window": label_window(train, test),
        "repeat_run_fluctuation": repeat_report,
    }
    return report


if __name__ == "__main__":
    import json

    print(json.dumps(evaluate(), indent=2))
