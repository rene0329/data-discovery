#!/usr/bin/env python3
import csv
import decimal
import json
import sys
from pathlib import Path

MAX_VECTOR = 8


def fail(message):
    print(message, file=sys.stderr)
    raise SystemExit(64)


def main():
    if len(sys.argv) != 5:
        fail("usage: csv_to_input.py TEMPLATE INPUT OUTPUT FIELDS_JSON")
    template, source_name, output_name, fields_json = sys.argv[1:]
    try:
        fields = json.loads(fields_json)
    except (TypeError, ValueError):
        fail("FIELDS_JSON must be a JSON array")
    if (not isinstance(fields, list) or not fields
            or not all(isinstance(name, str) and name for name in fields)):
        fail("field bindings must be a non-empty string array")
    if len(set(fields)) != len(fields):
        fail("field bindings must be unique")
    vector_length = len(fields)
    if not 1 <= vector_length <= MAX_VECTOR:
        fail("field bindings must contain between 1 and 8 columns")
    source = Path(source_name)
    if not source.is_file():
        fail("authorized input material has not been staged")
    with source.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames:
            fail("input CSV requires a header")
        rows = list(reader)
    if len(rows) != 1:
        fail("MP-SPDZ fixed templates require exactly one CSV data row per party")
    missing = [name for name in fields if name not in reader.fieldnames]
    if missing:
        fail("input CSV does not contain every frozen field binding")
    values = []
    for name in fields:
        raw = rows[0].get(name, "")
        if raw is None or not raw.strip():
            fail("numeric fields cannot be empty")
        try:
            value = decimal.Decimal(raw)
        except decimal.InvalidOperation:
            fail("input contains a non-numeric value")
        if not value.is_finite():
            fail("input contains a non-finite value")
        if template in ("secure-sum-3p-v1", "private-threshold-3p-v1") and value != value.to_integral_value():
            fail("integer template received a fractional value")
        values.append(format(value, "f"))
    if template == "private-threshold-3p-v1" and vector_length != 1:
        fail("threshold template requires exactly one field binding")
    values.extend("0" for _ in range(MAX_VECTOR - len(values)))
    destination = Path(output_name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(" ".join(values) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
