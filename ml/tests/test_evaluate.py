"""Reproducibility and point-in-time hygiene checks for the evaluation pipeline."""

import numpy as np

from ml_eval.evaluate import (
    EMBEDDING_DIMS,
    SEED,
    build_category_affinity,
    generate_synthetic,
    java_string_hash,
    label_window,
    ndcg_at_k,
    popularity_scores,
    recommend_embedding,
    recommend_personal,
    recommend_popularity,
    temporal_split,
    text_embedding,
)


def test_generation_is_deterministic():
    a_categories, _, a_interactions = generate_synthetic()
    b_categories, _, b_interactions = generate_synthetic()
    assert np.array_equal(a_categories, b_categories)
    assert a_interactions[:50] == b_interactions[:50]


def test_temporal_split_is_ordered_by_time():
    _, _, interactions = generate_synthetic()
    small = interactions[:5000]
    train, test = temporal_split(small, train_ratio=0.8)
    assert max(i.occurred_at for i in train) <= min(i.occurred_at for i in test)
    window = label_window(train, test)
    assert window["no_future_leakage"] is True


def test_text_embedding_matches_backend_java_hash_model():
    # The backend EmbeddingServiceImpl lowercases, tokenises on non-word
    # boundaries and buckets with Math.floorMod(String.hashCode(), 64).
    # The evaluator must score the SAME model — not an md5 lookalike.
    import math

    def java_hash(s: str) -> int:
        h = 0
        for ch in s:
            h = (31 * h + ord(ch)) & 0xFFFFFFFF
        return h - 2**32 if h >= 2**31 else h

    vec = text_embedding("Music Festivals, tech-talks!")
    assert vec.shape == (EMBEDDING_DIMS,)
    assert math.isclose(float(np.linalg.norm(vec)), 1.0, rel_tol=1e-9)
    for token in ("music", "festivals", "tech", "talks"):
        assert vec[java_hash(token) % EMBEDDING_DIMS] > 0
    # md5 bucketing would place tokens elsewhere; anchor one bucket explicitly.
    assert java_string_hash("music") % EMBEDDING_DIMS == java_hash("music") % 64


def test_personalized_beats_popularity_without_the_oracle():
    event_categories, _user_taste, interactions = generate_synthetic()
    train, test = temporal_split(interactions)
    scores = popularity_scores(train)
    # V1 reads ONLY the train-derived affinity - the generating latent taste
    # stays out of the ranker, so the comparison is leakage-free.
    affinity = build_category_affinity(train, event_categories)

    truth_by_user: dict[int, set[int]] = {}
    for i in test:
        truth_by_user.setdefault(i.user_idx, set()).add(i.event_idx)
    users = sorted(truth_by_user.keys())[:300]

    pop = np.mean([
        ndcg_at_k(recommend_popularity(u, scores), truth_by_user[u])
        for u in users
    ])
    v1 = np.mean([
        ndcg_at_k(recommend_personal(u, scores, event_categories, affinity[u]),
                  truth_by_user[u])
        for u in users
    ])
    assert v1 >= pop, "personalized pipeline should not lose to the popularity baseline on synthetic taste"
    assert SEED == 20260830


def test_embedding_recommender_is_serving_parity_and_cold_safe():
    event_categories, _user_taste, interactions = generate_synthetic()
    small = interactions[:20000]
    train, _test = temporal_split(small)
    scores = popularity_scores(train)
    affinity = build_category_affinity(train, event_categories)

    # A user with observed affinity gets an embedding-influenced ranking.
    warm_user = max(affinity.sum(axis=1).nonzero()[0], key=lambda u: affinity[u].sum())
    warm = recommend_embedding(warm_user, scores, event_categories, affinity[warm_user])
    assert len(warm) == 10

    # A cold user (no train interactions) falls back to popularity exactly.
    all_users = {i.user_idx for i in train}
    cold_user = next(u for u in range(1000) if u not in all_users)
    base = list(np.argsort(-scores)[:10])
    assert recommend_embedding(cold_user, scores, event_categories, np.zeros(5)) == base