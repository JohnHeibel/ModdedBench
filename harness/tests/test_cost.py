# SPDX-License-Identifier: LGPL-3.0-or-later
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool  # noqa: E402
from mbtools_gtnh import core


class CostTests(unittest.TestCase):
    def cost(self, entries, now, **kw):
        with tempfile.TemporaryDirectory() as d:
            log = Path(d) / 'calls.jsonl'
            log.write_text(''.join(json.dumps(e) + '\n' for e in entries) + 'not json\n', encoding='utf-8')
            with mock.patch.object(mbtool, 'CALL_LOG', str(log)), mock.patch.object(core.time, 'time', return_value=now):
                return core.mb_cost(**kw)

    def test_rollup_by_tool_and_method_with_overlap_counted_once(self):
        entries = [dict(t=0, s=10, tool='mb_mine', method=None, error=None),             # outside a 1 h window
                   dict(t=7000, s=60, tool='mb_mine', method=None, error=None),
                   dict(t=7030, s=60, tool='mb_obs', method='player', error=None),        # overlaps the mine call
                   dict(t=7100, s=1, tool='mb_notes', method='get', error='bad_request'),
                   dict(t=7110, s=1, tool='mb_notes', method='get', error=None)]
        out = self.cost(entries, now=7200, hours=1)
        self.assertEqual(out['calls'], 4)
        self.assertEqual(out['failed'], 1)
        self.assertEqual(out['toolMinutes'], round(92 / 60, 1))  # 7000..7090 plus two 1 s calls
        self.assertEqual(out['betweenCallsMinutes'], round(108 / 60, 1))
        self.assertEqual(list(out['top'])[0], 'mb_notes(get)')
        self.assertEqual(out['top']['mb_notes(get)'], '2 calls, 1 failed, 0.0 min')
        self.assertIn('mb_obs(player)', out['top'])
        self.assertNotIn('resultChars', out)  # a log written before result sizes were recorded

    def test_result_text_is_summed_and_the_heaviest_tools_named_with_their_largest_result(self):
        entries = [dict(t=7000, s=1, tool='mb_quest_lines', method=None, error=None, chars=90000),
                   dict(t=7010, s=1, tool='mb_quest_lines', method=None, error=None, chars=10000),
                   dict(t=7020, s=1, tool='mb_obs', method='player', error=None, chars=500)]
        out = self.cost(entries, now=7200, hours=1)
        self.assertEqual(out['resultChars'], 100500)
        self.assertEqual(list(out['topChars'].items())[0], ('mb_quest_lines', '100000 chars in 2 calls, largest 90000'))

    def test_no_log_yet(self):
        with mock.patch.object(mbtool, 'CALL_LOG', str(Path(tempfile.gettempdir()) / 'mb-no-such-log.jsonl')):
            self.assertEqual(core.mb_cost()['calls'], 0)


if __name__ == '__main__':
    unittest.main()
