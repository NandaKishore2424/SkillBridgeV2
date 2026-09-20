#!/usr/bin/env bash
#
# Compares every path the React app calls against every path the Spring
# controllers expose, and reports the two kinds of drift:
#
#   PHANTOM   the frontend calls it, the backend does not implement it
#             -> a 404 or 405 in production
#   ORPHAN    the backend exposes it, nothing calls it
#             -> not a bug, but often dead code or an unfinished feature
#
# Exits non-zero when there are phantoms, so CI can fail on one being
# reintroduced. Orphans are reported but never fail the build: a legitimately
# unused endpoint is a normal thing to have.
#
# The matching is deliberately structural rather than textual -- path variables
# on both sides are normalised to a wildcard, because `/batches/{id}` and
# `/batches/${batchId}` are the same endpoint spelled two ways.
set -uo pipefail
cd "$(dirname "$0")/.."

BACKEND=skillbridge-backend/src/main/java
FRONTEND=skillbridge-frontend/src

python3 - "$BACKEND" "$FRONTEND" <<'PY'
import os, re, sys

backend_root, frontend_root = sys.argv[1], sys.argv[2]

METHODS = "Get|Post|Put|Patch|Delete"

def norm(path):
    """Collapse path variables so both sides compare equal."""
    path = re.sub(r'\$\{[^}]*\}', '{}', path)   # `${batchId}` template literal
    path = re.sub(r'\{[^}]*\}', '{}', path)     # `{id}` / `{batchId:\\d+}`
    path = re.sub(r'/+', '/', path)
    return path.rstrip('/') or '/'

# --- template variables that are a closed set of strings ---------------------
#
# Not every `${x}` is an id. bulk-upload.ts builds its paths from
#
#     export type ImportKind = 'students' | 'trainers'
#
# so `/admin/${kind}/bulk-upload` is not one fuzzy endpoint, it is exactly two
# real ones -- and the backend implements both. Collapsing that segment to `{}`
# reported all four of that file's calls as phantoms, because no backend path
# has a variable there; the build failed on endpoints that exist.
#
# Expanding instead makes the check stricter, not looser. `/admin/{}/bulk-upload`
# asks whether *something* is there. Two literal paths ask whether each one is.
UNION = re.compile(r'\btype\s+(\w+)\s*=\s*((?:\s*\'[^\']+\'\s*\|)+\s*\'[^\']+\')')

def literal_segments(src):
    """Template variable name -> the strings it can be, within one file."""
    unions = {name: re.findall(r"'([^']+)'", body) for name, body in UNION.findall(src)}
    if not unions:
        return {}
    bound = {}
    # `kind: ImportKind` in a parameter list or a declaration. A variable typed
    # as one of this file's unions can only ever be one of those strings.
    for var, type_name in re.findall(r'\b(\w+)\s*:\s*(\w+)\b', src):
        if type_name in unions:
            bound[var] = unions[type_name]
    return bound

def expand(path, bound):
    """Every path this template can produce at run time, normalised."""
    paths = [path]
    for var, values in bound.items():
        placeholder = '${%s}' % var
        if not any(placeholder in p for p in paths):
            continue
        paths = [p.replace(placeholder, value) for p in paths for value in values]
    return {norm(p) for p in paths}

# --- backend: @RequestMapping base + each @<Verb>Mapping ---------------------
backend = set()
for dirpath, _, files in os.walk(backend_root):
    for name in files:
        if not name.endswith("Controller.java"):
            continue
        src = open(os.path.join(dirpath, name)).read()
        m = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"', src)
        base = m.group(1) if m else ""
        # The optional group matters: `@GetMapping` with no parentheses at all
        # is legal and common for the collection endpoint on a base path. An
        # earlier version of this regex required the parens and so reported six
        # perfectly real endpoints as phantoms.
        for verb, sub in re.findall(
                r'@(%s)Mapping\b(?:\(\s*(?:value\s*=\s*)?(?:"([^"]*)")?)?' % METHODS, src):
            backend.add((verb.upper(), norm(base + (sub or ""))))

# --- frontend: apiClient/api calls -------------------------------------------
# Matches api.get('/x'), apiClient.post(`/x/${id}`), axios.delete("..."), etc.
CALL = re.compile(
    r'\b(?:api|apiClient|axios|client)\s*\.\s*(get|post|put|patch|delete)\s*'
    # generics may nest -- PagedResponse<Batch> -- so match balanced-ish, not [^>]*
    r'(?:<(?:[^<>]|<[^<>]*>)*>\s*)?\(\s*[`\'"]([^`\'"]+)[`\'"]',
    re.I)

frontend = {}
for dirpath, _, files in os.walk(frontend_root):
    for name in files:
        if not name.endswith((".ts", ".tsx")):
            continue
        rel = os.path.relpath(os.path.join(dirpath, name))
        src = open(os.path.join(dirpath, name)).read()
        bound = literal_segments(src)
        for verb, path in CALL.findall(src):
            if not path.startswith('/'):
                continue
            # The axios instance is configured with baseURL .../api/v1
            for resolved in expand('/api/v1' + path, bound):
                frontend.setdefault((verb.upper(), resolved), set()).add(rel)

phantoms = sorted(k for k in frontend if k not in backend)
orphans  = sorted(k for k in backend if k not in frontend)

print(f"backend endpoints : {len(backend)}")
print(f"frontend calls    : {len(frontend)}")
print()

if phantoms:
    print(f"PHANTOM — called by the frontend, not implemented ({len(phantoms)}):")
    for verb, path in phantoms:
        print(f"  {verb:<6} {path}")
        for f in sorted(frontend[(verb, path)]):
            print(f"           called from {f}")
    print()
else:
    print("PHANTOM — none. Every frontend call resolves.\n")

if orphans:
    print(f"ORPHAN — implemented, nothing calls it ({len(orphans)}). Informational:")
    for verb, path in orphans:
        print(f"  {verb:<6} {path}")

sys.exit(1 if phantoms else 0)
PY
