import pytest

from statslib.core import mean, median, spread, summary


def test_mean():
    assert mean([1, 2, 3, 4]) == 2.5


def test_median_odd_length():
    assert median([3, 1, 2]) == 2


def test_median_even_length():
    # The middle two values are 2 and 3, so the median is their average.
    assert median([1, 2, 3, 4]) == 2.5


def test_median_even_length_unsorted():
    assert median([10, 2, 8, 4]) == 6.0


def test_spread():
    assert spread([4, 1, 9]) == 8


def test_summary():
    assert summary([1, 2, 3, 4]) == {"mean": 2.5, "median": 2.5, "spread": 3, "n": 4}


def test_empty_raises():
    with pytest.raises(ValueError):
        median([])
