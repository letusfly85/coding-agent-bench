"""Parse the pipe-delimited records used by the ingest pipeline."""

FIELDS = ("id", "name", "score", "tags")


def split_record(line):
    """Split one raw line into its fields.

    Records look like:  12 | alice | 87 | red;blue
    Surrounding whitespace on each field is not significant.
    """
    return [part.strip() for part in line.split("|")]


def parse_tags(raw):
    """Tags are semicolon-separated. An empty field means no tags."""
    if not raw:
        return []
    return [t.strip() for t in raw.split(";") if t.strip()]


def parse_record(line):
    parts = split_record(line)
    if len(parts) != len(FIELDS):
        raise ValueError(f"expected {len(FIELDS)} fields, got {len(parts)}: {line!r}")
    return {
        "id": int(parts[0]),
        "name": parts[1],
        "score": int(parts[2]),
        "tags": parse_tags(parts[3]),
    }


def parse_all(lines):
    out = []
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        out.append(parse_record(line))
    return out
