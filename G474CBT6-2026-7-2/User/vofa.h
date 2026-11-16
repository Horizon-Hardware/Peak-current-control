//
// Created by CRB on 2026/8/29.
//

#ifndef G474CBT6_2026_7_2_VOFA_H
#define G474CBT6_2026_7_2_VOFA_H

#include <stdint.h>
#include "usart.h"

#define VOFA_MAX_CHANNELS      20    // JustFloat 单次发送的最大通道数
#define VOFA_TEXT_BUF_SIZE     256   // FireWater 文本协议的最大单行缓冲区大小

void VOFA_JustFloat(UART_HandleTypeDef *huart, uint8_t channels_num, ...);

#endif //G474CBT6_2026_7_2_VOFA_H