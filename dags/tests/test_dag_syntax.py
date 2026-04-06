from __future__ import annotations

import py_compile
from pathlib import Path
import unittest


class DagSyntaxTest(unittest.TestCase):
    def test_all_dag_python_files_compile(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        dags_root = repo_root / "dags"

        python_files = [
            path
            for path in dags_root.rglob("*.py")
            if "__pycache__" not in path.parts
        ]

        self.assertGreater(len(python_files), 0, "Expected Python files under dags/")

        failures: list[str] = []
        for file_path in python_files:
            try:
                py_compile.compile(str(file_path), doraise=True)
            except py_compile.PyCompileError as exc:
                failures.append(f"{file_path.relative_to(repo_root)}: {exc.msg}")

        if failures:
            self.fail("\n".join(failures))


if __name__ == "__main__":
    unittest.main()
