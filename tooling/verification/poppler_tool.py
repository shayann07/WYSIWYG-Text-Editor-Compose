"""Poppler discovery for optional independent diagnostic checks, not an export backend."""
import os
from pathlib import Path
import shutil
import subprocess


def probe_pdftoppm():
    configured = os.environ.get('POPPLER_PDFTOPPM')
    value = configured or shutil.which('pdftoppm')
    if not value:
        raise RuntimeError('pdftoppm unavailable: install Poppler or set POPPLER_PDFTOPPM to its executable')
    path = Path(value).expanduser().resolve()
    if not path.is_file():
        raise RuntimeError(f'POPPLER_PDFTOPPM/PATH executable does not exist: {path}; correct the configured path')
    try:
        result = subprocess.run([str(path), '-v'], capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired) as failure:
        raise RuntimeError(f'Cannot run pdftoppm at {path}: {failure}; check executable/platform/permissions') from failure
    version = result.stdout + result.stderr
    if result.returncode != 0 or 'pdftoppm' not in version.lower():
        raise RuntimeError(f'pdftoppm version check failed at {path} (exit {result.returncode}): {version.strip()}')
    return path, version
