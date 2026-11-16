/**
 * @file    bsp_dwt.c
 * @brief   基于 Cortex-M DWT 周期计数器(CYCCNT)的精确时间工具集
 *
 * 原理：
 *   DWT->CYCCNT 是 CPU 内核的周期计数器，每个内核时钟 +1，频率等于 CPU 主频。
 *   它比 SysTick/HAL_Delay 精度高得多（可达 ns 级），常用于：
 *     - 高精度时间戳 / 时间线
 *     - 微秒/毫秒/秒级精确延时
 *     - 代码段执行耗时测量（性能分析）
 *     - 无阻塞超时判断
 *
 * 关键点：CYCCNT 只有 32 位，在 170MHz 下约 25 秒回绕一次。本文件用
 *   CYCCNT_RoundCount 记录溢出次数，把 32 位计数器扩展成 64 位绝对时间线。
 *   因此必须周期性调用 DWT_SysTimeUpdate()（建议放进 SysTick 中断），
 *   否则两次调用间隔超过一次回绕周期时，溢出轮数会漏记、时间会跳变。
 */
#include "BSP_DWT.h"

DWT_Time_t SysTime;              /*!< 当前系统时间（秒/毫秒/微秒拆分），由 DWT_SysTimeUpdate 刷新 */

static uint32_t CPU_FREQ_Hz;     /*!< CPU 主频，单位 Hz（每周期 1 个计数值） */
static uint32_t CPU_FREQ_Hz_ms;  /*!< 每毫秒对应的计数值 */
static uint32_t CPU_FREQ_Hz_us;  /*!< 每微秒对应的计数值 */

static uint32_t CYCCNT_RoundCount = 0;  /*!< 32 位 CYCCNT 已溢出的次数（高 32 位） */
static uint32_t CYCCNT_LAST = 0;        /*!< 上一次读到的 CYCCNT，用于检测回绕 */
uint64_t CYCCNT64 = 0;                  /*!< 扩展后的 64 位绝对周期计数（高32位=轮数，低32位=当前值） */

/**
 * @brief  更新 32 位 CYCCNT 的溢出轮数（内部函数）
 * @param  cnt_now 本次读到的 DWT->CYCCNT 值
 *
 * 通过比较本次与上次的计数值判断是否发生回绕：若 cnt_now < 上次值，
 * 说明计数器已从 0xFFFFFFFF 回绕到 0，溢出轮数 +1。这是拼装 64 位
 * 时间线的核心，必须在关中断下调用，避免中途被打断导致漏记溢出。
 */
static inline void DWT_CNT_Update(uint32_t cnt_now)
{
    if (cnt_now < CYCCNT_LAST) {
        CYCCNT_RoundCount++;
    }
    CYCCNT_LAST = cnt_now;
}

/**
 * @brief  初始化 DWT 周期计数器
 * @param  CPU_Freq_MHz CPU 主频，单位 MHz（例如 170MHz 传 170）
 *
 * 依次完成：使能 DWT 跟踪使能位(TRCENA)、清零并启动 CYCCNT 计数、
 * 缓存三种时间刻度对应的计数值，最后复位 64 位时间线。
 */
void DWT_Init(uint32_t CPU_Freq_MHz)
{
    /* 使能 DWT/CoreDebug 跟踪 */
    CoreDebug->DEMCR |= CoreDebug_DEMCR_TRCENA_Msk;

    /* Cortex-M7/M33 上 DWT 寄存器默认锁定，需先解锁；G4(M4) 无需此步 */
    #if defined(__CORTEX_M) && (__CORTEX_M == 7 || __CORTEX_M == 33)
    DWT->LAR = 0xC5ACCE55;
    #endif

    /* 清零计数器并开启 CYCCNT 计数 */
    DWT->CYCCNT = 0u;
    DWT->CTRL |= DWT_CTRL_CYCCNTENA_Msk;

    /* 缓存三种刻度的计数值：Hz、每毫秒、每微秒 */
    CPU_FREQ_Hz = CPU_Freq_MHz * 1000000;
    CPU_FREQ_Hz_ms = CPU_FREQ_Hz / 1000;
    CPU_FREQ_Hz_us = CPU_FREQ_Hz / 1000000;

    /* 复位 64 位时间线（临界区保护） */
    uint32_t primask = __get_PRIMASK();
    __disable_irq();
    CYCCNT_RoundCount = 0;
    CYCCNT_LAST = DWT->CYCCNT;
    CYCCNT64 = 0;
    __set_PRIMASK(primask);
}

/**
 * @brief  刷新系统时间（把当前周期计数折算成 秒/毫秒/微秒）
 *
 * 读取 CYCCNT 并更新溢出轮数，拼成 64 位周期计数 CYCCNT64，
 * 除以每微秒计数值得到总微秒数，再拆分成 SysTime 的 s/ms/us 三档。
 * 建议放入 SysTick 中断周期性调用，保证溢出轮数不漏记。
 */
void DWT_SysTimeUpdate(void)
{
    uint32_t primask = __get_PRIMASK();
    __disable_irq();

    uint32_t cnt_now = DWT->CYCCNT;
    DWT_CNT_Update(cnt_now);

    /* 高 32 位放溢出轮数，低 32 位放当前计数值，组成 64 位时间线 */
    CYCCNT64 = ((uint64_t)CYCCNT_RoundCount << 32) | cnt_now;
    uint64_t total_us = CYCCNT64 / CPU_FREQ_Hz_us;

    __set_PRIMASK(primask);

    /* 总微秒数 → 秒 / 毫秒 / 微秒 */
    SysTime.s  = (uint32_t)(total_us / 1000000);
    SysTime.ms = (uint16_t)((total_us % 1000000) / 1000);
    SysTime.us = (uint16_t)(total_us % 1000);
}

/**
 * @brief  测量距上次记录点的时间差（单精度，返回秒）
 * @param  cnt_last [in/out] 上次记录的 CYCCNT，调用后被更新为当前值
 * @retval 两次调用间经过的时间，单位秒(float)
 *
 * 适合连续测量多个代码段：第一次调用前给 *cnt_last 赋初值(如 DWT->CYCCNT)，
 * 之后每次调用都会把 *cnt_last 刷新为当前值，返回该区间耗时。
 * 单精度浮点，精度约 7 位有效数字；需要更高精度用 DWT_GetDeltaT64。
 */
float DWT_GetDeltaT(uint32_t *cnt_last)
{
    uint32_t primask = __get_PRIMASK();
    __disable_irq();
    uint32_t cnt_now = DWT->CYCCNT;
    DWT_CNT_Update(cnt_now);
    __set_PRIMASK(primask);

    float dt = (float)(cnt_now - *cnt_last) / CPU_FREQ_Hz;
    *cnt_last = cnt_now;
    return dt;
}

/**
 * @brief  测量距上次记录点的时间差（双精度，返回秒）
 * @param  cnt_last [in/out] 上次记录的 CYCCNT，调用后被更新为当前值
 * @retval 两次调用间经过的时间，单位秒(double)
 *
 * 功能同 DWT_GetDeltaT，但返回 double，精度更高，适合累加或长时间测量。
 */
double DWT_GetDeltaT64(uint32_t *cnt_last)
{
    uint32_t primask = __get_PRIMASK();
    __disable_irq();
    uint32_t cnt_now = DWT->CYCCNT;
    DWT_CNT_Update(cnt_now);
    __set_PRIMASK(primask);

    double dt = (double)(cnt_now - *cnt_last) / CPU_FREQ_Hz;
    *cnt_last = cnt_now;
    return dt;
}

/**
 * @brief  获取自 DWT_Init 以来的绝对时间（单位秒，double）
 * @retval 系统上电/初始化后经过的秒数
 */
double DWT_GetTimeline_s(void) {
    DWT_SysTimeUpdate();
    return (double)SysTime.s + (double)SysTime.ms * 0.001 + (double)SysTime.us * 0.000001;
}

/**
 * @brief  获取自 DWT_Init 以来的绝对时间（单位毫秒，double）
 * @retval 系统上电/初始化后经过的毫秒数
 */
double DWT_GetTimeline_ms(void){
    DWT_SysTimeUpdate();
    return (double)SysTime.s * 1000.0 + (double)SysTime.ms + (double)SysTime.us * 0.001;
}

/**
 * @brief  获取自 DWT_Init 以来的绝对时间（单位微秒，uint64_t 整数）
 * @retval 系统上电/初始化后经过的微秒数
 *
 * 返回 64 位整数微秒，无浮点精度损失，是超时判断/时间戳的首选基准。
 */
uint64_t DWT_GetTimeline_us(void) {
    DWT_SysTimeUpdate();
    return (uint64_t)SysTime.s * 1000000 + SysTime.ms * 1000 + SysTime.us;
}


/**
 * @brief  阻塞式微秒延时
 * @param  uSec 延时时长，单位微秒
 *
 * 轮询 CYCCNT 直到经过指定周期数。超过 1000000us(1s) 时按 1s 分片处理，
 * 避免 uSec * CPU_FREQ_Hz_us 在 uint32_t 中溢出。延时期间 CPU 空转，不释放。
 */
void DWT_Delay_us(uint32_t uSec)
{
    /* 大于 1s 的部分按 1s 分片，防止 32 位乘法溢出 */
    while (uSec > 1000000) {
        uint32_t start_tick = DWT->CYCCNT;
        uint32_t chunk_ticks = 1000000 * CPU_FREQ_Hz_us;
        while ((DWT->CYCCNT - start_tick) < chunk_ticks) {
        }
        uSec -= 1000000;
    }

    /* 剩余不足 1s 的部分 */
    uint32_t start_tick = DWT->CYCCNT;
    uint32_t delay_ticks = uSec * CPU_FREQ_Hz_us;
    while ((DWT->CYCCNT - start_tick) < delay_ticks) {
    }
}

/** @brief 阻塞式毫秒延时（支持小数），转成微秒后调用 DWT_Delay_us */
void DWT_Delay_ms(float Delay) { DWT_Delay_us((uint32_t)(Delay * 1000.0f)); }
/** @brief 阻塞式秒延时（支持小数），转成微秒后调用 DWT_Delay_us */
void DWT_Delay_s(float Delay)  { DWT_Delay_us((uint32_t)(Delay * 1000000.0f)); }


/**
 * @brief  基于 64 位绝对时间线，设置一个无阻塞超时
 * @param  timeout 超时结构体句柄
 * @param  ms 超时时长，单位毫秒（支持小数）
 *
 * 记录调用时刻的绝对微秒时间戳作为起点，并保存换算成微秒的时长。
 * 配合 DWT_Check_Timeout 使用，突破 32 位时间差约 10 秒的限制，
 * 可表达任意长度超时，且不阻塞程序。
 */
void DWT_Set_Timeout(DWT_Timeout_t *timeout, float ms) {
    timeout->start_time_us = DWT_GetTimeline_us();
    timeout->delay_us = (uint64_t)(ms * 1000.0f);
}

/**
 * @brief  检查无阻塞超时是否已到
 * @param  timeout 超时结构体句柄
 * @retval true 已超时；false 未超时
 *
 * 用当前绝对时间减去起始时间，与设定时长比较。可在主循环/状态机中
 * 反复轮询而不阻塞执行。
 */
bool DWT_Check_Timeout(DWT_Timeout_t *timeout) {
    return ((DWT_GetTimeline_us() - timeout->start_time_us) >= timeout->delay_us);
}

/* ================== 代码执行性能分析 ================== */

/**
 * @brief  开始测量某段代码的执行耗时（记录起始周期计数值）
 * @param  profiler 性能分析结构体句柄
 *
 * 在待测代码段之前调用，之后调用 DWT_Profile_Stop 得到耗时。
 */
void DWT_Profile_Start(DWT_Profiler_t *profiler) {
    profiler->start_tick = DWT->CYCCNT;
}

/**
 * @brief  结束测量并计算代码段耗时
 * @param  profiler 性能分析结构体句柄
 *
 * 计算自 DWT_Profile_Start 以来经过的周期数，换算成微秒(cost_us)
 * 和毫秒(cost_ms)存入结构体。
 */
void DWT_Profile_Stop(DWT_Profiler_t *profiler) {
    uint32_t delta_tick = DWT->CYCCNT - profiler->start_tick;
    profiler->cost_us = (float)delta_tick / CPU_FREQ_Hz_us;
    profiler->cost_ms = (float)delta_tick / CPU_FREQ_Hz_ms;
}
