"""Shared reader for OpenSki session exports (a ZIP, or a directory holding the CSV files). numpy only."""
import csv
import io
import os
import zipfile

import numpy as np

TEXT_COLUMNS = {"side", "source", "calibration", "near_range", "missed_samples"}


def read_text(path, name):
    """Return the text of `name` (for example 'samples.csv') from a ZIP or a directory, or None if it is absent."""
    if os.path.isdir(path):
        p = os.path.join(path, name)
        if not os.path.exists(p):
            return None
        with open(p, "r", encoding="utf-8") as f:
            return f.read()
    with zipfile.ZipFile(path) as z:
        names = {os.path.basename(n): n for n in z.namelist()}
        if name not in names:
            return None
        return z.read(names[name]).decode("utf-8")


def parse_csv(text):
    """Header-keyed columns: numeric columns become float arrays (empty field -> nan), the TEXT_COLUMNS stay string arrays."""
    rows = list(csv.reader(io.StringIO(text)))
    if not rows:
        return {}
    header, body = rows[0], [r for r in rows[1:] if r]
    cols = {}
    for i, h in enumerate(header):
        raw = [r[i] if i < len(r) else "" for r in body]
        if h in TEXT_COLUMNS:
            cols[h] = np.array(raw, dtype=object)
        else:
            cols[h] = np.array([float(v) if v not in ("", None) else np.nan for v in raw], dtype=float)
    return cols


def load(path, name):
    t = read_text(path, name)
    return None if t is None else parse_csv(t)


def write_csvs(dirpath, files):
    """files: {name: (header_list, rows)}; used by the selftests to build a directory export."""
    os.makedirs(dirpath, exist_ok=True)
    for name, (header, rows) in files.items():
        with open(os.path.join(dirpath, name), "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            w.writerow(header)
            w.writerows(rows)
