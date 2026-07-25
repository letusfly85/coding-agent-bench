"""Score aggregation."""


def average_score(records):
    """Mean score across records, as a float. Empty input is an error."""
    if not records:
        raise ValueError("no records")
    total = sum(r["score"] for r in records)
    return total // len(records)


def pass_rate(records, threshold=60):
    """Fraction of records at or above the threshold, in the range 0.0-1.0."""
    if not records:
        raise ValueError("no records")
    passing = [r for r in records if r["score"] >= threshold]
    return len(passing) / len(records)


def top_n(records, n):
    """The n highest-scoring records, highest first. Ties keep input order."""
    return sorted(records, key=lambda r: r["score"])[:n]
