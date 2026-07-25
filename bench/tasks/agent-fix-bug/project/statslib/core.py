"""Small statistics helpers."""


def mean(values):
    if not values:
        raise ValueError("mean() of empty sequence")
    return sum(values) / len(values)


def median(values):
    if not values:
        raise ValueError("median() of empty sequence")
    ordered = sorted(values)
    n = len(ordered)
    mid = n // 2
    if n % 2 == 1:
        return ordered[mid]
    return ordered[mid]


def spread(values):
    if not values:
        raise ValueError("spread() of empty sequence")
    return max(values) - min(values)


def summary(values):
    return {
        "mean": mean(values),
        "median": median(values),
        "spread": spread(values),
        "n": len(values),
    }
