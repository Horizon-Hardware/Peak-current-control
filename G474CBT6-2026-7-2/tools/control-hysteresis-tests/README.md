# 双向控制迟滞离线检查

在工程根目录运行：

```powershell
python tools/control-hysteresis-tests/run.py
```

需要电脑端GCC（本机`D:/mingw64/bin/gcc.exe`）。脚本从`User/task.c`提取实际调制、方向配置、PI及`Loop_Control()`函数，从`Core/Src/main.c`读取PI增益；使用真实芯片/HAL的结构和常量，重定向到电脑上的模拟寄存器。

输出和日志位于`build/hysteresis-validation/`。检查双向模式回差、噪声下实际DAC/CMP保持、功率平衡回差、硬件换向次数、快速请求、满电/低压恢复和限流等行为。平均电流模型仅用于功能回归，不包含实际200 kHz电感纹波、比较器延迟、死区、MOS或电源噪声，因此不能证明实物稳定性或消除啸叫。

新增27 V电容/70 W底盘测试，检查需要10 W补能时的持续PWM。通过两个不同的谷值参考零点偏置模型，验证PI输出可以跨零校正而不更改硬件放电方向；另检查原始ADC不被覆盖、PI读取滤波反馈及两支路使用相同滤波。零点偏置模型是用于暴露启停逻辑缺陷的假设，不代表测得了实物偏置。

可选开关模型扫描：

```powershell
python tools/control-hysteresis-tests/run.py --switch
```

`switch_scan.c`使用10 ns时间步长、10 μH/80 mΩ电感、80 ns死区、理想体二极管及CMP2间隔的DAC步进近似，扫描22/24 V输入、18～27.5 V充电以及10.5～27 V放电，含±0.1 V交替采样扰动。结果在`switch_validation.log`。每次固定CAP电压，不模拟CAP电压自身动态、真实ADC窗口、比较器/驱动延迟和开关噪声；该模型不能复现用户的全部硬件症状。

不访问调试器、不读写实物MCU，不下载、复位或暂停目标。固件另用`cmake --build --preset Debug -j 4`编译。
