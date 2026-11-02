"""The actual observer must avoid process dumps and reject late completed reads."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from android_memory_readiness import observe_memory, parse_kernel, MemoryServiceUnavailable

GLOBAL = (Path(__file__).parent / 'fixtures/android-memory/api37-responsive.txt').read_bytes()
KERNEL = b'''MemTotal: 6000000 kB
MemFree: 100000 kB
MemAvailable: 3000000 kB
Buffers: 100 kB
Cached: 2000000 kB
SwapTotal: 1000000 kB
SwapFree: 900000 kB
Slab: 10000 kB
KernelStack: 1000 kB
PageTables: 2000 kB
CommitLimit: 4000000 kB
Committed_AS: 5000000 kB
VmallocTotal: 13743895347199 kB
HugePages_Total: 0
'''


class ActualKernelObservation(unittest.TestCase):
    def test_independent_kernel_values_allow_legitimate_zero_and_overcommit(self):
        self.assertEqual({'MemTotalKiB':6000000, 'MemFreeKiB':100000, 'MemAvailableKiB':3000000,
                          'BuffersKiB':100, 'CachedKiB':2000000, 'SwapTotalKiB':1000000,
                          'SwapFreeKiB':900000}, parse_kernel(KERNEL, 0))
        zero = KERNEL.replace(b'MemFree: 100000', b'MemFree: 0').replace(b'SwapTotal: 1000000', b'SwapTotal: 0').replace(b'SwapFree: 900000', b'SwapFree: 0')
        self.assertEqual(0, parse_kernel(zero, 0)['MemFreeKiB'])

    def test_denied_truncated_duplicate_invalid_units_utf8_status_and_bounds_block(self):
        for raw in [b'', b'cat: /proc/meminfo: Permission denied\n', KERNEL[:-1], KERNEL[:100],
                    KERNEL+b'MemFree: 1 kB\n', KERNEL+b'HugePages_Total: 1\n',
                    KERNEL.replace(b'MemTotal: 6000000 kB',b'MemTotal: 6000000 MB'),
                    KERNEL.replace(b'MemFree: 100000',b'MemFree: -1'),
                    KERNEL.replace(b'MemTotal: 6000000',b'MemTotal: 0'),
                    KERNEL.replace(b'MemFree: 100000',b'MemFree: 7000000'),
                    KERNEL.replace(b'SwapFree: 900000',b'SwapFree: 2000000'),
                    KERNEL+b'Bad: 999999999999999999999999999999 kB\n',
                    KERNEL+b'Bad: \xff\n', KERNEL*1000, GLOBAL]:
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                parse_kernel(raw,0)
        for code, stderr in [(1,b''),(-9,b''),(0,b'Permission denied')]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                parse_kernel(KERNEL,code,stderr)

    def test_actual_observer_reads_kernel_once_without_global_process_dump(self):
        calls = []
        def probe(label, *args, **kwargs):
            calls.append(args)
            if args == ('shell', 'getprop', 'ro.build.version.sdk'):
                return b'37'
            return subprocess.CompletedProcess(args, 0, KERNEL if args[0] == 'exec-out' else GLOBAL, b'')
        with tempfile.TemporaryDirectory() as directory:
            observe_memory(probe, directory, 'observe', 100, clock=lambda: 0)
        self.assertEqual([('shell', 'getprop', 'ro.build.version.sdk'), ('exec-out', 'cat', '/proc/meminfo')], calls)

    def test_actual_observer_cannot_admit_success_delivered_after_deadline(self):
        now = [0]
        def probe(label, *args, **kwargs):
            if args == ('shell', 'getprop', 'ro.build.version.sdk'):
                return b'37'
            now[0] = 11
            return subprocess.CompletedProcess(args, 0, KERNEL if args[0] == 'exec-out' else GLOBAL, b'')
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(MemoryServiceUnavailable, 'deadline'):
                observe_memory(probe, directory, 'observe', 10, clock=lambda: now[0])
