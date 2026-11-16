//
// Created by CRB on 2026/8/14.
//

#ifndef G474CBT6_2026_7_2_TASK_H
#define G474CBT6_2026_7_2_TASK_H

#include "opamp.h"
#include "adc.h"
#include "dac.h"
#include "hrtim.h"
#include "dma.h"
#include "usart.h"
#include "loop.h"
#include "comp.h"

#define PERIOD	27200
#define	CMP_A   HRTIM1->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_A].CMP1xR
#define CMP_B   HRTIM1->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_B].CMP1xR

#define k_CHASSIS_volt 1.0f
#define b_CHASSIS_volt 0.0f

#define k_CAP_volt 1.0f
#define b_CAP_volt 0.0f

#define k_BAT_current 1.081356f
#define b_BAT_current 1.111327f

#define k_LL_current 1.007915f
#define b_LL_current 0.087894f

#define true  1
#define false 0

#define DAC_CURRENT_ZERO_CODE       2048.0f//DAC零点计数值 3.3/1.65 = 0.5
#define DAC_CURRENT_COUNTS_PER_A    (0.005f * 20.0f * 4096.0f / 3.3f)//每1安电流对应多少个DAC计数值=124
#define PEAK_CURRENT_MIN_A          (-DAC_CURRENT_ZERO_CODE / DAC_CURRENT_COUNTS_PER_A)//峰值最小的电流=-16.5A
#define PEAK_CURRENT_MAX_A          ((4095.0f - DAC_CURRENT_ZERO_CODE) / DAC_CURRENT_COUNTS_PER_A)//峰值最大的电流=16.5A
#define CONTROL_LOOP_DIVIDER        10U//200kHz PWM / 10 = 20kHz控制中断
#define POWER_LOOP_DIVIDER          100U//20kHz / 100 = 200Hz功率外环
#define DAC_SLOPE_STEP              200U//Buck高占空比增强补偿；充电A侧/放电B侧共用
#define DAC_BOOST_SLOPE_STEP        180U//Boost斜坡步长
#define CHARGE_PEAK_CURRENT_MAX_A   15.0f//正向电流指令上限
#define DISCHARGE_VALLEY_MAX_A      15.0f//反向电流指令幅值上限
#define CAP_DISCHARGE_MIN_V         10.0f
#define CHARGE_RESTART_POWER_RATIO  0.8f//放电截止后，底盘低于预算80%才恢复充电
#define DISCHARGE_BOOST_CMP_MAX     21760U//反向Boost最长储能窗口，支持10V升至24V
#define DISCHARGE_BOOST_CMP_MARGIN  2720U//在伏秒平衡估计上留10%周期的电流调节余量
#define CAP_VOLTAGE_MAX_V           30.0f
#define CAP_CHARGE_STOP_MARGIN_V   0.05f//达到目标+50mV，或目标附近CV请求趋零，进入满电保持
#define CAP_CHARGE_RESTART_DROP_V  0.50f//满电保持后回落0.5V才恢复充电，28V目标对应27.5V
#define CAP_CV_WINDOW_V            0.20f//距目标0.2V内允许CV接管，区外先建立充电电流
#define BUCK_BOOST_RATIO_LOW        0.85f//目标侧/源侧电压比低于此值使用Buck
#define BUCK_BOOST_RATIO_HIGH       1.05f//保留原过渡区，24V输入时充电为20.4～25.2V
#define MODE_RATIO_FILTER_ALPHA    0.10f//只滤模式电压比，20kHz下时间常数约0.5ms
#define MODE_RATIO_HYSTERESIS      0.015f//电压比单边回差；24V源侧约0.36V，作用于实际DAC/CMP
#define MODE_BLEND_MAX_STEP        0.01f//每50us最多变化1%，完整交接至少5ms
#define POWER_BALANCE_ENTER_W      2.0f//偏离预算至少2W才从停充停放区重新启动
#define POWER_BALANCE_EXIT_RATIO   0.25f//回到0.5W以内停止；小预算时进入回差按预算5%缩小
#define CURRENT_PWM_START_A        0.05f//充电PWM启动门槛；放电方向由功率需求锁定
#define CURRENT_PWM_STOP_A         0.01f//充电电流请求降到此幅值时停止PWM
#define CURRENT_FEEDBACK_ALPHA     0.25f//20kHz下电流反馈滤波时间常数约0.15ms
#define DISCHARGE_ZERO_TRIM_MAX_A   2.0f//允许谷值参考跨过零点微调；不能再用其符号判断充放电
#define BUCK_CMP_MAX                24480U//A侧原90%比较点，实际脉宽还需扣CMP4和死区
#define BOOST_CMP_MIN               2720U//B侧固定刷新比较点，沿用Buck测试的10%
#define BOOST_CMP_MAX               13600U//正向Boost储能比较点上限，给22V升28V留压降/死区余量
#define DISCHARGE_BOOST_CMP_BASE    10000U//反向窗口仍沿用原下限，不随正向升压扩展
#define IL_TO_DAC(x) ((x) * DAC_CURRENT_COUNTS_PER_A + DAC_CURRENT_ZERO_CODE)//将PI输出的目标峰值电流转换成DAC计数值

extern volatile float V_target;
extern volatile float I_BAT_target;
extern volatile float P_BAT_target;
extern volatile float I_BAT_ref;
extern volatile float I_target;

typedef enum {
    CONTROL_MODE_CV = 0,
    CONTROL_MODE_CC
} ControlMode;

typedef enum {
    CONTROL_MODE_BUCK = 2,
    CONTROL_MODE_BUCKBOOST,
    CONTROL_MODE_BOOSTBUCK,
    CONTROL_MODE_BOOST
} PowerStageMode;

typedef enum {
    ENERGY_DISCHARGE = -1,  //放电
    ENERGY_CHARGE = 1       //充电
} EnergyDirection;

typedef enum {
    POWER_REQUEST_DISCHARGE = -1,
    POWER_REQUEST_IDLE = 0,
    POWER_REQUEST_CHARGE = 1
} PowerRequest;

typedef struct {
    float V_CHASS;  //底盘电压
    float V_CAP;    //电容电压
} ADC_DATA_VOLT;

typedef struct {
    float I_BAT;   // R13: total battery current, positive from battery to loads
    float I_LL;    // R17: DCDC input current, positive when charging CAP
}ADC_DATA_CURRENT;

typedef struct {
    float P_BAT;    //电池功率
    float P_CHASS;  //底盘功率
    float P_LL;    //DCDC输入功率；CAP输出功率还需扣除变换器损耗
} ADC_DATA_POWER;

typedef struct {
    volatile uint32_t adc_data_raw[2];
    uint16_t adc_data_mid[4];
    ADC_DATA_VOLT volt;
    ADC_DATA_CURRENT current;
    ADC_DATA_POWER power;
}ADC_DATA;

typedef struct {
    float K_p;
    float K_i;

    float target;
    float actual;
    float error[2];

    float output;
    float output_max;
    float output_min;
}PI_Controller;

typedef struct {
    uint8_t protect_flag;
    uint8_t AWD1_flag;
}Loop_Protect;

typedef struct {
    Loop_Protect loop_protect;
    volatile float V_target;
    volatile float I_BAT_target;
    volatile float P_BAT_target;
    volatile float I_BAT_ref;
    volatile float I_target;   //DAC电流参考；放电零点微调可略为正，方向看energy_direction
    volatile float Duty;       // V_CAP/V_CHASS电压比，不是实测PWM占空比
    volatile float mode_blend; // 0=Buck，1=Boost，中间为双桥过渡
    volatile float mode_ratio_filtered; //只用于模式交接；电压PI使用原采样
    volatile uint8_t pwm_enabled;//使能PWM
    volatile float I_applied;  //送给当前硬件方向的DAC参考，不是实测平均电流
    volatile EnergyDirection energy_direction;//硬件目前配置好的方向
    volatile uint8_t discharge_cutoff; //10V截止锁住PWM，底盘低于预算80%解除
    volatile uint8_t charge_complete; //满电保持停充；不阻止底盘超预算时放电
    volatile float mode_ratio_applied; //带记忆回差后的模式电压比，实际DAC/CMP均使用它
    volatile PowerRequest power_request; //功率回差状态：1充电，0停充停放，-1补能
    volatile float I_BAT_feedback; //供PI/功率判向使用的电池电流；原始ADC读数保留
    volatile float I_LL_feedback;  //与电池电流使用同一滤波系数，避免支路相减时相位不一致
}Loop_variable;

extern volatile ControlMode control_mode;
extern volatile PowerStageMode power_stage_mode;
extern DMA_HandleTypeDef hdma_adc1;

void TIM_Init();

void ADC_Init();/*采样+环路*/
void ADC_Convert();
void ANO_SendData(uint8_t fun, const uint8_t *data, uint8_t len);

void DAC_Init();

void PI_Controller_Init(PI_Controller *PI_Type,float K_p,float K_i);
void PI_Controller_Calculate(PI_Controller *PI_Type,float target,float actual);

void Loop_Control();
void loop_text();

#endif //G474CBT6_2026_7_2_TASK_H
