from __future__ import annotations

from pathlib import Path

import pytest

from scripts.bench.async_bench import (
    MIN_P99_SAMPLES,
    ensure_archive_available,
    percentile,
    result_paths,
    summarize_health,
    validate_run_id,
)


def test_percentile_uses_linear_interpolation() -> None:
    assert percentile([1.0, 2.0, 3.0, 4.0, 5.0], 0.95) == pytest.approx(4.8)


def test_p99_is_hidden_when_samples_are_insufficient() -> None:
    summary = summarize_health([float(i) for i in range(MIN_P99_SAMPLES - 1)])

    assert summary["health_p99_ms"] is None
    assert summary["health_p99_note"] == f"표본 부족(n<{MIN_P99_SAMPLES})"


def test_p99_is_reported_without_collapsing_to_max_at_minimum_sample_count() -> None:
    samples = [float(i) for i in range(1, MIN_P99_SAMPLES + 1)]
    summary = summarize_health(samples)

    assert summary["health_p99_ms"] == pytest.approx(99.01)
    assert summary["health_p99_ms"] != summary["health_max_ms"]
    assert summary["health_p99_note"] is None


@pytest.mark.parametrize("run_id", ["20260801T120000Z", "review-2", "windows_20core"])
def test_validate_run_id_accepts_safe_names(run_id: str) -> None:
    assert validate_run_id(run_id) == run_id


@pytest.mark.parametrize("run_id", ["", "../escape", "run id", "a/b"])
def test_validate_run_id_rejects_unsafe_names(run_id: str) -> None:
    with pytest.raises(ValueError):
        validate_run_id(run_id)


def test_duplicate_run_id_fails_before_measurement(tmp_path: Path) -> None:
    _, archive = result_paths(tmp_path, "review-run")
    archive.parent.mkdir(parents=True)
    archive.write_text("existing", encoding="utf-8")

    with pytest.raises(FileExistsError, match="run-id archive already exists"):
        ensure_archive_available(tmp_path, "review-run")
