# -*- coding: UTF-8 -*-
import importlib
from pathlib import Path

from fastapi import FastAPI

app = FastAPI()

_routes_dir = Path(__file__).parent / "routes"
for _f in sorted(_routes_dir.glob("*.py")):
    if _f.stem.startswith("_"):
        continue
    _mod = importlib.import_module(f"fredica_pyutil_server.routes.{_f.stem}")
    if hasattr(_mod, "_router"):
        # noinspection PyProtectedMember
        app.include_router(_mod._router)

if __name__ == '__main__':
    pass
