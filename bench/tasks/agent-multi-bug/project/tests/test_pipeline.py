import pytest

from app.calc import average_score, pass_rate, top_n
from app.parser import parse_all, parse_record, parse_tags
from app.report import render

SAMPLE = [
    "# id | name | score | tags",
    "1 | alice | 90 | red;blue",
    "2 | bob   | 55 | ",
    "3 | carol | 72 | green",
    "",
    "4 | dave  | 41 | red",
]


def test_parse_tags_empty():
    assert parse_tags("") == []


def test_parse_tags_splits():
    assert parse_tags("red; blue") == ["red", "blue"]


def test_parse_record():
    assert parse_record("7 | eve | 63 | x;y") == {
        "id": 7, "name": "eve", "score": 63, "tags": ["x", "y"],
    }


def test_parse_all_skips_blank_and_comments():
    records = parse_all(SAMPLE)
    assert [r["id"] for r in records] == [1, 2, 3, 4]


def test_parse_all_rejects_short_rows():
    with pytest.raises(ValueError):
        parse_all(["1 | alice | 90"])


def test_pass_rate():
    # 90 and 72 clear the threshold of 60; 55 and 41 do not.
    assert pass_rate(parse_all(SAMPLE)) == 0.5


def test_average_score_is_a_float():
    # 90 + 55 + 72 + 41 == 258, over 4 records.
    assert average_score(parse_all(SAMPLE)) == 64.5


def test_top_n_is_highest_first():
    assert [r["id"] for r in top_n(parse_all(SAMPLE), 2)] == [1, 3]


def test_render_orders_by_descending_score():
    rows = [ln for ln in render(parse_all(SAMPLE)).splitlines()
            if ln.strip() and ln.strip()[0].isdigit()]
    assert [int(ln.split()[0]) for ln in rows] == [1, 3, 2, 4]


def test_render_includes_average():
    assert "average: 64.5" in render(parse_all(SAMPLE))
