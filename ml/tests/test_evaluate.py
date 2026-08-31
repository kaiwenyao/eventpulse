"""Reproducibility and point-in-time hygiene checks for the evaluation pipeline."""

import numpy as np

from ml_eval.evaluate import (
    SEED,
    generate_synthetic,
    ndcg_at_k,
    popularity_scores,
    recommend_personal,
    recommend_popularity,
    temporal_split,
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


def test_personalized_beats_popularity_on_synthetic_taste():
    event_categories, user_taste, interactions = generate_synthetic()
    train, test = temporal_split(interactions)
    scores = popularity_scores(train)

    truth_by_user: dict[int, set[int]] = {}
    for i in test:
        truth_by_user.setdefault(i.user_idx, set()).add(i.event_idx)
    users = sorted(truth_by_user.keys())[:300]

    pop = np.mean([
        ndcg_at_k(recommend_popularity(u, scores), truth_by_user[u])
        for u in users
    ])
    v1 = np.mean([
        ndcg_at_k(recommend_personal(u, scores, event_categories, user_taste), truth_by_user[u])
        for u in users
    ])
    assert v1 >= pop, "personalized pipeline should not lose to the popularity baseline on synthetic taste"
    assert SEED == 20260830
