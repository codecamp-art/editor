from __future__ import annotations

import os
import unittest
from pathlib import Path


class DagBagImportTest(unittest.TestCase):
    def test_all_dags_import_cleanly(self) -> None:
        try:
            from airflow.models import DagBag
        except Exception as exc:  # pragma: no cover - env-dependent
            self.skipTest(f"Airflow not available in test environment: {exc}")

        repo_root = Path(__file__).resolve().parents[2]
        dags_root = repo_root / "dags"

        os.environ.setdefault("AIRFLOW__CORE__LOAD_EXAMPLES", "False")

        dagbag = DagBag(dag_folder=str(dags_root), include_examples=False)

        self.assertEqual(
            {},
            dagbag.import_errors,
            f"DAG import errors detected: {dagbag.import_errors}",
        )
        self.assertGreater(len(dagbag.dags), 0, "Expected at least one DAG to be loaded")


if __name__ == "__main__":
    unittest.main()
