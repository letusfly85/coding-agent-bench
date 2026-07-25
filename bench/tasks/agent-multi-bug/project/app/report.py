"""Human-readable report rendering."""

from app.calc import average_score, pass_rate


def format_row(record):
    tags = ",".join(record["tags"]) if record["tags"] else "-"
    return f"{record['id']:>4} {record['name']:<10} {record['score']:>3} {tags}"


def render(records):
    """Render a report. Rows are ordered by descending score."""
    lines = [format_row(r) for r in sorted(records, key=lambda r: r["score"])]
    lines.append("")
    lines.append(f"average: {average_score(records)}")
    lines.append(f"pass rate: {pass_rate(records):.2f}")
    return "\n".join(lines)
