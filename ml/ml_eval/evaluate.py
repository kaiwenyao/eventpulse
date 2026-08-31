"""Offline, reproducible recommendation evaluation on synthetic data.

This module is the standalone evaluation pipeline for plan §10.3:
point-in-time features, temporal split, popularity baseline vs embedding
similarity, coverage/diversity, bootstrap confidence intervals. Every result
is labelled SYNTHETIC PIPELINE EVALUATION - it says nothing about real
commercial effect.

Run:  uv run python -m ml_eval.evaluate   (or pytest for the smoke test)
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

import numpy as np

SEED = 20260830
N_USERS = 10_000
N_EVENTS = 5_000
N_INTERACTIONS = 500_000
CATEGORIES = ["music", "tech", "sports", "art", "food"]
TOP_K = 10


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


def text_embedding(text: str, dims: int = 64) -> np.ndarray:
    """Deterministic feature-hash embedder, mirroring the backend's EmbeddingService."""
    vec = np.zeros(dims)
    for token in text.split():
        h = int(hashlib.md5(token.encode()).hexdigest(), 16)
        vec[h % dims] += 1.0
    norm = np.linalg.norm(vec)
    return vec / norm if norm else vec


def recommend_popularity(user_idx: int, scores: np.ndarray, k: int = TOP_K) -> list[int]:
    return list(np.argsort(-scores)[:k])


def recommend_personal(user_idx: int, scores: np.ndarray, event_categories: np.ndarray,
                       user_taste: np.ndarray, k: int = TOP_K) -> list[int]:
    """V1-lite: popularity prior boosted by category affinity (deterministic)."""
    boost = np.zeros(N_EVENTS)
    for category in user_taste[user_idx]:
        boost += (event_categories == category).astype(float) * 2.0
    combined = np.log1p(scores) + boost
    return list(np.argsort(-combined)[:k])


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


def bootstrap_ci(metric_per_user: list[float], n_boot: int = 200) -> tuple[float, float]:
    rng = np.random.default_rng(SEED)
    values = np.asarray(metric_per_user)
    if values.size == 0:
        return (0.0, 0.0)
    samples = rng.choice(values, size=(n_boot, values.size), replace=True).mean(axis=1)
    return (float(np.percentile(samples, 2.5)), float(np.percentile(samples, 97.5)))


def evaluate() -> dict:
    """Full offline evaluation report. Output must always carry the synthetic label."""
    event_categories, user_taste, interactions = generate_synthetic()
    train, test = temporal_split(interactions)

    truth_by_user: dict[int, set[int]] = {}
    for i in test:
        truth_by_user.setdefault(i.user_idx, set()).add(i.event_idx)
    sampled_users = sorted(truth_by_user.keys())[:2000]  # keep runtime bounded

    scores = popularity_scores(train)

    pop_recs, pop_ndcg, pop_recall = [], [], []
    v1_recs, v1_ndcg, v1_recall = [], [], []
    for user_idx in sampled_users:
        p_rec = recommend_popularity(user_idx, scores)
        v_rec = recommend_personal(user_idx, scores, event_categories, user_taste)
        truth = truth_by_user[user_idx]
        pop_recs.append(p_rec)
        v1_recs.append(v_rec)
        pop_ndcg.append(ndcg_at_k(p_rec, truth))
        pop_recall.append(recall_at_k(p_rec, truth))
        v1_ndcg.append(ndcg_at_k(v_rec, truth))
        v1_recall.append(recall_at_k(v_rec, truth))

    report = {
        "label": "SYNTHETIC PIPELINE EVALUATION - does not represent real commercial effect",
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
    }
    return report


if __name__ == "__main__":
    import json

    print(json.dumps(evaluate(), indent=2))
