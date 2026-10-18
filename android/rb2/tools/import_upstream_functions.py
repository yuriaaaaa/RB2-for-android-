#!/usr/bin/env python3
"""Writes rb2_functions.toml from upstream's rb2_config.toml.

Upstream's config holds the full function list from its codegen run (address and size of
every function). This port keeps the addresses and sizes and drops the names: the
rex_* names marked upstream's hand-written hooks, which rb2_rexcrt.toml now covers, and
the rest are the default sub_XXXXXXXX names anyway.

Usage: import_upstream_functions.py <upstream rb2_config.toml> <out rb2_functions.toml>
"""
import re
import sys

ENTRY = re.compile(r'^(0x[0-9A-Fa-f]{8})\s*=\s*\{[^}]*\bsize\s*=\s*(0x[0-9A-Fa-f]+)')


def main() -> int:
    src, dst = sys.argv[1], sys.argv[2]
    entries = []
    in_functions = False
    with open(src, encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if stripped.startswith("["):
                in_functions = stripped == "[functions]"
                continue
            if in_functions:
                m = ENTRY.match(stripped)
                if m:
                    entries.append((int(m.group(1), 16), int(m.group(2), 16)))
    entries.sort()
    with open(dst, "w", encoding="utf-8") as out:
        out.write("# Function boundaries from upstream's rb2_config.toml "
                  "(tools/import_upstream_functions.py).\n")
        out.write("[functions]\n")
        for addr, size in entries:
            out.write(f"0x{addr:08X} = {{ size = 0x{size:X} }}\n")
    print(f"{len(entries)} functions -> {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
