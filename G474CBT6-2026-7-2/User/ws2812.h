//
// Created by CRB on 2026/8/28.
//
// WS2812 单灯驱动头文件
// 方案：TIM3_CH1(PB4) 输出 800kHz PWM，DMA 逐周期搬运占空比，硬件生成时序。
//       TIM3 与 GPIO 由 CubeMX 配置，DMA 句柄用 CubeMX 生成的 hdma_tim3_ch1。
//

#ifndef G474CBT6_2026_7_2_WS2812_H
#define G474CBT6_2026_7_2_WS2812_H

#include "main.h"
#include "BSP_DWT.h"
#include "tim.h"
#include "dma.h"

/* ==================== 时序参数(定时器时钟 170MHz) ==================== */
/* CubeMX 里 TIM3 需设：Prescaler=0，Counter Period=211(或 212)，Pulse=0 */

/* 0 码高电平 CCR 值 ≈ 0.4us */
#define WS2812_BIT_0            68U
/* 1 码高电平 CCR 值 ≈ 0.8us */
#define WS2812_BIT_1            136U
/* 复位低电平周期数 ≈ 80us(WS2812 要求 ≥50us) */
#define WS2812_RESET_PULSES     64U

#define LED_RED    0
#define LED_GREEN  1
#define LED_BLUE   2
#define LED_YELLOW 3
#define LED_PURPLE 4
#define LED_CYAN   5
#define LED_WRITE  6
#define LED_Light_green  7
#define LED_Orange 8
#define LED_Off_white 9

/* ==================== 对外接口 ==================== */

/* 初始化(熄灭单灯) */
void WS2812_Init(void);
/* 设置单灯颜色(仅写缓冲，需调用 WS2812_Refresh) */
void WS2812_SetAll(uint8_t r, uint8_t g, uint8_t b);
/* 设置全局亮度 0~255，默认 255 */
void WS2812_SetBrightness(uint8_t brightness);
/* 把当前颜色发送到灯(阻塞到本帧发完) */
void WS2812_Refresh(void);

void WS2812_shimmer_Refresh(uint8_t color,uint8_t power,uint16_t ms);

#endif //G474CBT6_2026_7_2_WS2812_H
