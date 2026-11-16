"""Compile the actual control functions with mock registers; never access a probe."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
output = root / 'build/hysteresis-validation'
output.mkdir(parents=True, exist_ok=True)
source = (root / 'User/task.c').read_text(encoding='utf-8-sig')
source = source[:source.index('void TIM_Init()')] + source[
    source.index('void PI_Controller_Init('):source.index('void loop_text()')]
source = source.replace('#include "task.h"',
    '#include "task.h"\nextern HRTIM_TypeDef test_hrtim;\n#undef HRTIM1\n#define HRTIM1 (&test_hrtim)', 1)
(output / 'actual_control.c').write_text(source, encoding='utf-8')
main = (root / 'Core/Src/main.c').read_text(encoding='utf-8-sig')
gains = re.findall(r'PI_Controller_Init\(&PI_(Volt|Current|Power)_Controller,\s*([^,]+),\s*([^\)]+)\)', main)
assert len(gains) == 3, 'Read the actual three PI initializers from main.c'
(output / 'gains.h').write_text(''.join(
    f'#define {name.upper()}_KP {kp}\n#define {name.upper()}_KI {ki}\n' for name, kp, ki in gains), encoding='utf-8')
includes = ['User', 'Core/Inc', 'Drivers/STM32G4xx_HAL_Driver/Inc',
            'Drivers/CMSIS/Device/ST/STM32G4xx/Include', 'Drivers/CMSIS/Include']
command = ['gcc', '-O2', '-std=c11', '-DSTM32G474xx', '-DUSE_HAL_DRIVER',
           '-ffunction-sections', '-fdata-sections', '-Wno-pointer-to-int-cast', '-Wno-int-to-pointer-cast']
command += [f'-I{root / path}' for path in includes] + [f'-I{output}']
command += [str(Path(__file__).with_name('check.c')), '-Wl,--gc-sections', '-lm',
            '-o', str(output / 'check.exe')]
build = subprocess.run(command, capture_output=True, text=True)
(output / 'compile.log').write_text(build.stdout + build.stderr, encoding='utf-8')
if build.returncode:
    raise SystemExit(build.stdout + build.stderr)
result = subprocess.run([str(output / 'check.exe')], capture_output=True, text=True)
(output / 'validation.log').write_text(result.stdout + result.stderr, encoding='utf-8')
print(result.stdout + result.stderr, end='')
if result.returncode:
    raise SystemExit(result.returncode)
if '--switch' in sys.argv:
    switch_command = command.copy()
    switch_command[switch_command.index(str(Path(__file__).with_name('check.c')))] = str(Path(__file__).with_name('switch_scan.c'))
    switch_command[-1] = str(output / 'switch_scan.exe')
    subprocess.run(switch_command, check=True, capture_output=True, text=True)
    log = []
    for cap, load, voltage in [(18,10,24), (20.4,10,24), (22.8,10,24), (24,10,24),
                              (25.2,10,24), (27,10,24), (27.5,10,22), (27,70,24),
                              (26,70,24), (25.5,70,24), (24,70,24), (22,70,24),
                              (18,70,24), (10.5,70,24), (27,90,24), (24,90,24), (10.5,90,24)]:
        scan = subprocess.run([str(output / 'switch_scan.exe'), str(cap), str(load), '.25', '.1', str(voltage)],
                              capture_output=True, text=True)
        log.append(scan.stdout + scan.stderr)
        print(log[-1], end='', flush=True)
        if scan.returncode:
            (output / 'switch_validation.log').write_text(''.join(log), encoding='utf-8')
            raise SystemExit(scan.returncode)
    (output / 'switch_validation.log').write_text(''.join(log), encoding='utf-8')
