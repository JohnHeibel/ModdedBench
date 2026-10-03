# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""One background task's process: ``python harness/mcp/task.py <id>``, started by mb_run(background=True).

The work is in harness/tools/tasks.py, imported fresh here, so a new task always runs the current tool code.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mbtool  # noqa: E402,F401  (installs the tool package)
from mbtools_gtnh import tasks  # noqa: E402

if __name__ == "__main__":
    sys.exit(tasks.run(sys.argv[1]))
