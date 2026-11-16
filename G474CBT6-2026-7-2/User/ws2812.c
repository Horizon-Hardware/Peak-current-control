//
// Created by CRB on 2026/8/28.
//
// WS2812 单灯驱动实现
// 原理：
//   1. TIM3_CH1 由 CubeMX 配置为 800kHz PWM1(PB4 输出)。
//   2. 一个 PWM 周期代表一位数据：高电平时间 0.4us(0 码) / 0.8us(1 码)。
//   3. DMA(TIM3_UP)在每个"更新事件"把下一个比较值搬到 TIM3->CCR1，
//      连续输出一串不同占空比的方波 = 一帧数据(GRB，高位在前)。
//   4. 复位(≥50us 低电平)靠帧尾追加 CCR=0 周期实现。
//
// 关键点：时序完全由 PWM + DMA 硬件完成，不使用中断。
//   - 不用定时器中断翻转 GPIO/移位；
//   - DMA 完成也用轮询等待，不开 DMA 中断。
//   因此 CPU 被其他中断/临界区占用时，灯效也不受影响。
//

#include "ws2812.h"

/* ==================== 内部定义 ==================== */

#define WS2812_TIM              htim2            /* CubeMX 生成的 TIM3 句柄 */
#define WS2812_TIM_CHANNEL      TIM_CHANNEL_1
#define WS2812_TIM_DMA_REQ      TIM_DMA_UPDATE   /* 更新事件作为 DMA 请求 */

/* DMA 句柄：CubeMX 里选 TIM3_UP 时生成的名字是 hdma_tim3_up。
 * 如果你的版本生成的名字不同，改下面这一处即可。 */
extern DMA_HandleTypeDef hdma_tim2_up;  /* CubeMX 在 dma.c / tim.c 中定义 */
#define WS2812_DMA              hdma_tim2_up

/* 缓冲长度 = 24bit(GRB) + 复位周期 */
#define WS2812_BUF_SIZE         (24U + WS2812_RESET_PULSES)

/* ==================== 内部变量 ==================== */

static uint8_t  ws2812_rgb[3];                   /*!< [0]=R [1]=G [2]=B */
static uint16_t ws2812_dma_buf[WS2812_BUF_SIZE]; /*!< DMA 搬运的 CCR1 序列 */
static uint8_t  ws2812_brightness = 255U;        /*!< 全局亮度 0~255 */

/* ==================== 内部函数 ==================== */

/* 应用亮度(16 位乘法，右移 8 位) */
static inline uint8_t WS2812_ApplyBrightness(uint8_t val)
{
    return (uint8_t)(((uint16_t)val * ws2812_brightness) >> 8);
}

/* 打包：GRB 顺序、高位在前，帧尾补复位低电平 */
static void WS2812_BuildBuffer(void)
{
    uint16_t *p = ws2812_dma_buf;

    uint8_t g = WS2812_ApplyBrightness(ws2812_rgb[1]);
    uint8_t r = WS2812_ApplyBrightness(ws2812_rgb[0]);
    uint8_t b = WS2812_ApplyBrightness(ws2812_rgb[2]);

    for (int8_t bit = 7; bit >= 0; bit--)
        *p++ = ((g >> bit) & 0x01U) ? WS2812_BIT_1 : WS2812_BIT_0;
    for (int8_t bit = 7; bit >= 0; bit--)
        *p++ = ((r >> bit) & 0x01U) ? WS2812_BIT_1 : WS2812_BIT_0;
    for (int8_t bit = 7; bit >= 0; bit--)
        *p++ = ((b >> bit) & 0x01U) ? WS2812_BIT_1 : WS2812_BIT_0;

    for (uint16_t i = 0; i < WS2812_RESET_PULSES; i++)
        *p++ = 0U;
}

/* 等待 DMA 搬运完成(轮询传输完成标志，不开中断) */
static void WS2812_WaitDone(void)
{
    uint32_t tcflag = __HAL_DMA_GET_TC_FLAG_INDEX(&WS2812_DMA);

    while (!__HAL_DMA_GET_FLAG(&WS2812_DMA, tcflag))
    {
    }
    __HAL_DMA_CLEAR_FLAG(&WS2812_DMA, tcflag);

    /* 非中断模式下 HAL 不会自动复位句柄状态，手动复位以便下次启动 */
    WS2812_DMA.State = HAL_DMA_STATE_READY;
}

/* ==================== 对外接口 ==================== */

void WS2812_Init(void)
{
    WS2812_SetAll(0, 0, 0);
    WS2812_Refresh();
}

void WS2812_SetAll(uint8_t r, uint8_t g, uint8_t b)
{
    ws2812_rgb[0] = r;
    ws2812_rgb[1] = g;
    ws2812_rgb[2] = b;
}

void WS2812_SetBrightness(uint8_t brightness)
{
    ws2812_brightness = brightness;
}

void WS2812_Refresh(void)
{
    WS2812_BuildBuffer();

    /* 停止上次输出，复位计数器与比较值(输出保持低电平) */
    HAL_TIM_PWM_Stop(&WS2812_TIM, WS2812_TIM_CHANNEL);
    __HAL_TIM_SET_COUNTER(&WS2812_TIM, 0);
    __HAL_TIM_SET_COMPARE(&WS2812_TIM, WS2812_TIM_CHANNEL, 0);
    __HAL_TIM_CLEAR_FLAG(&WS2812_TIM, TIM_FLAG_UPDATE);
    __HAL_TIM_CLEAR_FLAG(&WS2812_TIM, TIM_FLAG_CC1);

    /* 启动 DMA(非中断)，目标地址 TIM3->CCR1 */
    HAL_DMA_Start(&WS2812_DMA,
                  (uint32_t)ws2812_dma_buf,
                  (uint32_t)&WS2812_TIM.Instance->CCR1,
                  WS2812_BUF_SIZE);

    /* 使能更新事件 DMA 请求并启动 PWM */
    __HAL_TIM_ENABLE_DMA(&WS2812_TIM, WS2812_TIM_DMA_REQ);
    HAL_TIM_PWM_Start(&WS2812_TIM, WS2812_TIM_CHANNEL);

    /* 等待本帧发完(轮询) */
    WS2812_WaitDone();

    /* 关闭 DMA 请求；CCR1 停在 0，输出保持低电平(帧间复位) */
    __HAL_TIM_DISABLE_DMA(&WS2812_TIM, WS2812_TIM_DMA_REQ);
}

void WS2812_shimmer_Refresh(uint8_t color,uint8_t power,uint16_t ms) {

    static float LED_CONFIG[10][3] ={
        {1, 0, 0},    // RED
        {0, 1, 0},    // GREEN
        {0, 0, 1},    // BLUE
        {1, 1, 0},    // YELLOW
        {1, 0, 1},    // PURPLE
        {0, 1, 1},    // CYAN
        {1, 1, 1},    // write
        {0.3f, 1.0f, 0.1f},    // 7: 嫩绿/青黄（绿色为主，稍微带一点红和极少地蓝）
        {1.0f, 0.5f, 0.0f},    // 8: 橙色 (Orange)
        {0.5f, 0.5f, 0.5f},    // 9: 暗白/灰色
    };

    WS2812_SetAll(LED_CONFIG[color][0]*power, LED_CONFIG[color][1]*power, LED_CONFIG[color][2]*power);
    WS2812_Refresh();
    DWT_Delay_ms(ms);

    WS2812_SetAll(0, 0, 0);
    WS2812_Refresh();
    DWT_Delay_ms(ms);
}