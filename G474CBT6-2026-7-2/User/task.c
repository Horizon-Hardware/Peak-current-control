//
// Created by CRB on 2026/8/14.
//``
#include "task.h"
#include <math.h>

ADC_DATA adc_data;
PI_Controller PI_Volt_Controller;
PI_Controller PI_Current_Controller;
PI_Controller PI_Power_Controller;

extern Compensator3P3Z_t comp_v;
volatile ControlMode control_mode = CONTROL_MODE_CV;
volatile PowerStageMode power_stage_mode = CONTROL_MODE_BUCK;

Loop_variable loop_var = {
    .V_target = 28.0f,
    .I_BAT_target = 5.0f,
    .P_BAT_target = 60.0f,
    .energy_direction = ENERGY_CHARGE
};

//限幅函数
static float Clamp(float value, float minimum, float maximum) {
    if (value < minimum) { return minimum; }
    if (value > maximum) { return maximum; }
    return value;
}

// S形交接：在0、1两端斜率为0，避免两路DAC在交接中点突然改变变化率。
static float SmoothStep(float value) {
    value = Clamp(value, 0.0f, 1.0f);
    return value * value * (3.0f - 2.0f * value);
}

// 只处理模式电压比，不限制电流请求速度；充电、放电共用。
static float PowerStage_Blend(float ratio) {
    float blend = loop_var.mode_blend;
    if (!loop_var.pwm_enabled) {
        // 启动/换向时按当前电压比重新定位，避免沿用旧方向的过渡进度。
        loop_var.mode_ratio_filtered = ratio;
        loop_var.mode_ratio_applied = ratio;
    } else {
        loop_var.mode_ratio_filtered += MODE_RATIO_FILTER_ALPHA * (ratio - loop_var.mode_ratio_filtered);
        // 有记忆的回差：小范围往返保持；超出回差后沿边界连续跟随，不跳变。
        loop_var.mode_ratio_applied = Clamp(loop_var.mode_ratio_applied,
                                            loop_var.mode_ratio_filtered - MODE_RATIO_HYSTERESIS,
                                            loop_var.mode_ratio_filtered + MODE_RATIO_HYSTERESIS);
    }
    float target = Clamp((loop_var.mode_ratio_applied - BUCK_BOOST_RATIO_LOW) /
                         (BUCK_BOOST_RATIO_HIGH - BUCK_BOOST_RATIO_LOW), 0.0f, 1.0f);
    if (!loop_var.pwm_enabled) {
        blend = target;
    } else {
        blend += Clamp(target - blend, -MODE_BLEND_MAX_STEP, MODE_BLEND_MAX_STEP);
    }
    loop_var.mode_blend = blend;
    if (blend <= 0.0f) { power_stage_mode = CONTROL_MODE_BUCK; }
    else if (blend < 0.5f) { power_stage_mode = CONTROL_MODE_BUCKBOOST; }
    else if (blend < 1.0f) { power_stage_mode = CONTROL_MODE_BOOSTBUCK; }
    else { power_stage_mode = CONTROL_MODE_BOOST; }
    return blend;
}

// 功率平衡点的三态回差。60W预算：<=58W开始充电，>=62W开始放电。
// 充电到59.5W、放电到60.5W时先停止，避免在60W附近反复改DAC极性。
static void PowerStage_UpdatePowerRequest(float chassis_power, float battery_budget) {
    float enter = fminf(POWER_BALANCE_ENTER_W, 0.05f * battery_budget);
    float exit = enter * POWER_BALANCE_EXIT_RATIO;
    float surplus = battery_budget - chassis_power;
    if (surplus >= enter) {
        loop_var.power_request = POWER_REQUEST_CHARGE;
    } else if (surplus <= -enter) {
        loop_var.power_request = POWER_REQUEST_DISCHARGE;
    } else if ((loop_var.power_request == POWER_REQUEST_CHARGE && surplus <= exit) ||
               (loop_var.power_request == POWER_REQUEST_DISCHARGE && surplus >= -exit)) {
        loop_var.power_request = POWER_REQUEST_IDLE;
    }
}

//充电放电电流指令转换成两路 DAC 阈值和两侧定时器比较值
static void Charge_ApplyPeak(float peak_current) {
    float blend = PowerStage_Blend(loop_var.Duty);
    float base_code = Clamp(IL_TO_DAC(peak_current), 0.0f, 4095.0f);//电流指令对应的基础DAC码
    //两路斜坡不同：将各自最大导通窗口内的下降量预加回起始阈值，
    //减少同一电流指令从A侧交给B侧时的阈值差，达到DAC上限仍钳位
    //加入斜坡补偿后的两路起始阈值，先把预计会下降的量加回来，让DAC从更高的位置开始下降
    float buck_peak_code = Clamp(base_code + (BUCK_CMP_MAX / 1000.0f) * (DAC_SLOPE_STEP / 16.0f), 0.0f, IL_TO_DAC(CHARGE_PEAK_CURRENT_MAX_A));
    float boost_peak_code = Clamp(base_code + (BOOST_CMP_MAX / 1000.0f) * (DAC_BOOST_SLOPE_STEP / 16.0f), 0.0f, IL_TO_DAC(CHARGE_PEAK_CURRENT_MAX_A));
    // 过渡前半段A侧使用补偿后的电流阈值，后半段B侧接管。
    // 非主控侧阈值逐渐抬高到满量程，使其通常由CMP1限定脉宽。
    float buck_code = buck_peak_code + (4095.0f - buck_peak_code) * SmoothStep(2.0f * blend - 1.0f);
    float boost_code = boost_peak_code + (4095.0f - boost_peak_code) * SmoothStep(1.0f - 2.0f * blend);
    MODIFY_REG(hdac1.Instance->STR1, DAC_STR1_STRSTDATA1, (uint32_t)buck_code);
    MODIFY_REG(hdac1.Instance->STR2, DAC_STR2_STRSTDATA2, (uint32_t)boost_code);
    CMP_A = BUCK_CMP_MAX;
    CMP_B = BOOST_CMP_MIN + (uint32_t)((BOOST_CMP_MAX - BOOST_CMP_MIN) * blend);
}

// 反向时B是电源侧Buck桥，A是母线侧Boost桥；负电流越小，放电越强。
static void Discharge_ApplyValley(float valley_current) {
    float blend = PowerStage_Blend(1.0f / fmaxf(loop_var.Duty, 0.001f));
    // 等压附近不需要10V放电时的长Boost窗口，否则两侧容易交替接管。
    // 由反向伏秒平衡估计CMP1位置，并留电流调节余量；低电压时才延长窗口。
    float start_cmp = (float)hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_A].CMP4xR;
    float boost_max = Clamp(start_cmp + PERIOD - (BUCK_CMP_MAX - start_cmp) /
                            fmaxf(loop_var.mode_ratio_applied, 0.001f) + DISCHARGE_BOOST_CMP_MARGIN,
                            DISCHARGE_BOOST_CMP_BASE, DISCHARGE_BOOST_CMP_MAX);
    // 谷值参考允许小幅跨零；零参考不等于平均电流为零，斜坡/纹波偏置由PI校正。
    float base_code = Clamp(IL_TO_DAC(valley_current), 0.0f, IL_TO_DAC(DISCHARGE_ZERO_TRIM_MAX_A));
    // 与充电对称：预减最大窗口内的上升量，避免上升斜坡过早结束反向储能。
    // 这是斜坡参考补偿，I_target并非严格的实测谷值/独立硬件过流阈值。
    float buck_valley_code = Clamp(base_code - (BUCK_CMP_MAX / 1000.0f) * (DAC_SLOPE_STEP / 16.0f), IL_TO_DAC(-DISCHARGE_VALLEY_MAX_A), 4095.0f);
    float boost_valley_code = Clamp(base_code - (boost_max / 1000.0f) * (DAC_BOOST_SLOPE_STEP / 16.0f), IL_TO_DAC(-DISCHARGE_VALLEY_MAX_A), 4095.0f);
    // 非主控侧趋向0码，使其通常由CMP1限制窗口；并非物理断开比较器。
    float buck_code = buck_valley_code * (1.0f - SmoothStep(2.0f * blend - 1.0f));
    float boost_code = boost_valley_code * (1.0f - SmoothStep(1.0f - 2.0f * blend));
    MODIFY_REG(hdac1.Instance->STR2, DAC_STR2_STRSTDATA2, (uint32_t)buck_code);
    MODIFY_REG(hdac1.Instance->STR1, DAC_STR1_STRSTDATA1, (uint32_t)boost_code);
    CMP_B = BUCK_CMP_MAX;
    CMP_A = BOOST_CMP_MIN + (uint32_t)((boost_max - BOOST_CMP_MIN) * blend);
}

static void PowerStage_Stop(void) {
    HAL_HRTIM_WaveformOutputStop(&hhrtim1, HRTIM_OUTPUT_TA1 | HRTIM_OUTPUT_TA2 |
                               HRTIM_OUTPUT_TB1 | HRTIM_OUTPUT_TB2);
    loop_var.pwm_enabled = false;
    loop_var.I_applied = 0.0f;
}

// 换向时先关闭四路PWM；只改变事件关系和DAC方向，保持门极高有效与互补死区。
static void PowerStage_SetDirection(EnergyDirection direction) {
    if (direction == ENERGY_DISCHARGE) {
        // COMP输出为低表示I_L<DAC；谷值事件结束负电流储能区间。
        SET_BIT(hhrtim1.Instance->sCommonRegs.EECR1, HRTIM_EECR1_EE1POL | HRTIM_EECR1_EE4POL);//比较器外部事件低电平有效
        //配置定时器置位与复位
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_A].SETx1R,//设置TIMA置位为CMP1和比较器事件
                  HRTIM_OUTPUTSET_TIMCMP1 | HRTIM_OUTPUTSET_EEV_4);
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_A].RSTx1R, HRTIM_OUTPUTRESET_TIMCMP4);
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_B].SETx1R, HRTIM_OUTPUTSET_TIMCMP4);
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_B].RSTx1R,
                  HRTIM_OUTPUTRESET_TIMCMP1 | HRTIM_OUTPUTRESET_EEV_1);
        //配置斜坡生成器步长和极性，复位值
        WRITE_REG(hdac1.Instance->STR1, (DAC_BOOST_SLOPE_STEP << DAC_STR1_STINCDATA1_Pos) |
                  DAC_SAWTOOTH_POLARITY_INCREMENT | (uint32_t)IL_TO_DAC(0.0f));
        WRITE_REG(hdac1.Instance->STR2, (DAC_SLOPE_STEP << DAC_STR2_STINCDATA2_Pos) |
                  DAC_SAWTOOTH_POLARITY_INCREMENT | (uint32_t)IL_TO_DAC(0.0f));
    } else {
        CLEAR_BIT(hhrtim1.Instance->sCommonRegs.EECR1, HRTIM_EECR1_EE1POL | HRTIM_EECR1_EE4POL);
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_A].SETx1R, HRTIM_OUTPUTSET_TIMCMP4);
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_A].RSTx1R,
                  HRTIM_OUTPUTRESET_TIMCMP1 | HRTIM_OUTPUTRESET_EEV_4);
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_B].SETx1R,
                  HRTIM_OUTPUTSET_TIMCMP1 | HRTIM_OUTPUTSET_EEV_1);
        WRITE_REG(hhrtim1.Instance->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_B].RSTx1R, HRTIM_OUTPUTRESET_TIMCMP4);
        WRITE_REG(hdac1.Instance->STR1, (DAC_SLOPE_STEP << DAC_STR1_STINCDATA1_Pos) | (uint32_t)IL_TO_DAC(0.0f));
        WRITE_REG(hdac1.Instance->STR2, (DAC_BOOST_SLOPE_STEP << DAC_STR2_STINCDATA2_Pos) | (uint32_t)IL_TO_DAC(0.0f));
    }
    loop_var.energy_direction = direction;
    if (direction == ENERGY_CHARGE) { Charge_ApplyPeak(0.0f); }
    else { Discharge_ApplyValley(0.0f); }
    HAL_HRTIM_SoftwareUpdate(&hhrtim1, HRTIM_TIMERUPDATE_A | HRTIM_TIMERUPDATE_B);
}

// 功率需求锁定方向，电流PI只调节该方向的DAC参考。
static void PowerStage_Apply(float request, float voltage_target) {
    // 功率需求决定硬件方向；谷值PI趋零/跨零不再反复切断小功率补能。
    if (loop_var.power_request == POWER_REQUEST_IDLE) {
        if (loop_var.pwm_enabled) { PowerStage_Stop(); }
        loop_var.I_applied = 0.0f;
        return;
    }
    EnergyDirection desired = loop_var.power_request == POWER_REQUEST_DISCHARGE ?
                              ENERGY_DISCHARGE : ENERGY_CHARGE;
    request = desired == ENERGY_DISCHARGE ? Clamp(request, -DISCHARGE_VALLEY_MAX_A, DISCHARGE_ZERO_TRIM_MAX_A) :
                                           Clamp(request, 0.0f, CHARGE_PEAK_CURRENT_MAX_A);

    //先判断这次请求的充放电方向。如果与当前硬件方向不同，就关闭 PWM、切换配置，并等下一次调用再启动
    if (desired != loop_var.energy_direction) {
        PowerStage_Stop();
        PowerStage_SetDirection(desired);
        loop_var.I_applied = 0.0f;
        return;
    }
    loop_var.I_applied = request;

    //PWM什么时候停止，停止以后什么时候允许重新启动，回差比较
    if (desired == ENERGY_CHARGE) {
        if (adc_data.volt.V_CAP >= voltage_target + 0.05f || request <= CURRENT_PWM_STOP_A) {
            PowerStage_Stop();
            return;
        }
        if (!loop_var.pwm_enabled &&
            (adc_data.volt.V_CAP >= voltage_target - 0.05f || request < CURRENT_PWM_START_A)) {
            loop_var.I_applied = 0.0f;
            return;
        }
        Charge_ApplyPeak(request);
    } else {
        // 放电由功率回差退出，或10V截止停止；小负请求/零点微调仍连续调制。
        if (adc_data.volt.V_CAP <= CAP_DISCHARGE_MIN_V || loop_var.discharge_cutoff) {
            PowerStage_Stop(); return;
        }
        Discharge_ApplyValley(request);
    }
    if (!loop_var.pwm_enabled) {
        HAL_HRTIM_WaveformOutputStart(&hhrtim1, HRTIM_OUTPUT_TA1 | HRTIM_OUTPUT_TA2 |
                                     HRTIM_OUTPUT_TB1 | HRTIM_OUTPUT_TB2);
        loop_var.pwm_enabled = true;
    }
}

void TIM_Init() {
    CMP_A = BUCK_CMP_MAX;
    CMP_B = BOOST_CMP_MIN;
    HAL_HRTIM_SoftwareUpdate(&hhrtim1, HRTIM_TIMERUPDATE_A | HRTIM_TIMERUPDATE_B);
    // 只对控制中断分频，两侧PWM及DAC斜坡仍为200kHz、保持180度相位差。
    hhrtim1.Instance->sMasterRegs.MREP = CONTROL_LOOP_DIVIDER - 1U;
    HAL_HRTIM_WaveformCounterStart(&hhrtim1,HRTIM_TIMERID_MASTER|HRTIM_TIMERID_TIMER_A|HRTIM_TIMERID_TIMER_B);
    // 首次有效采样、两环给出正电流请求后，再由Loop_Control启动PWM。
    __HAL_HRTIM_MASTER_ENABLE_IT(&hhrtim1, HRTIM_MASTER_IT_MREP);
}

void ADC_Init() {
    // HAL_SYSCFG_EnableVREFBUF();
    HAL_OPAMP_Start(&hopamp6);//底盘电压跟随器开启
    HAL_OPAMP_Start(&hopamp2);//电容电压跟随器开启
    HAL_ADCEx_Calibration_Start(&hadc1,ADC_SINGLE_ENDED);
    HAL_ADCEx_Calibration_Start(&hadc2,ADC_SINGLE_ENDED);

    HAL_ADCEx_MultiModeStart_DMA(&hadc1,(uint32_t *)adc_data.adc_data_raw,2);
    // 由HRTIM控制中断取样，不再产生每周期的DMA半帧/整帧中断。
    __HAL_DMA_DISABLE_IT(&hdma_adc1, DMA_IT_HT | DMA_IT_TC);
    HAL_ADC_Start(&hadc2);


}

void DAC_Init() {
    HAL_DACEx_SawtoothWaveGenerate(&hdac1, DAC_CHANNEL_1, DAC_SAWTOOTH_POLARITY_DECREMENT,
                                 (uint32_t)IL_TO_DAC(0.0f), DAC_SLOPE_STEP);
    HAL_DAC_Start(&hdac1, DAC_CHANNEL_1);

    HAL_DACEx_SawtoothWaveGenerate(&hdac1, DAC_CHANNEL_2, DAC_SAWTOOTH_POLARITY_DECREMENT,
                                 (uint32_t)IL_TO_DAC(0.0f), DAC_BOOST_SLOPE_STEP);
    HAL_DAC_Start(&hdac1, DAC_CHANNEL_2);
}

void ADC_Convert() {
    for (uint8_t i = 0; i < 2; i++) {
        adc_data.adc_data_mid[i] = adc_data.adc_data_raw[i] & 0xffff;
        adc_data.adc_data_mid[i+2] = (adc_data.adc_data_raw[i] >> 16) & 0xffff;
    }

    adc_data.volt.V_CHASS = k_CHASSIS_volt * ((adc_data.adc_data_mid[1]/4095.0f)*3.3f*16.0f) + b_CHASSIS_volt;
    adc_data.volt.V_CAP = k_CAP_volt * ((adc_data.adc_data_mid[3]/4095.0f)*3.3f*16.0f) + b_CAP_volt;
    adc_data.current.I_BAT = -(k_BAT_current * (((adc_data.adc_data_mid[0]/4095.0f)*3.3f - 1.65f)/20.0f/0.005f) + b_BAT_current);
    adc_data.current.I_LL = k_LL_current * (((adc_data.adc_data_mid[2]/4095.0f)*3.3f - 1.65f)/20.0f/0.005f) + b_LL_current;

    adc_data.power.P_BAT = adc_data.volt.V_CHASS * adc_data.current.I_BAT;
    adc_data.power.P_LL = adc_data.volt.V_CHASS * adc_data.current.I_LL;
    adc_data.power.P_CHASS = adc_data.volt.V_CHASS * (adc_data.current.I_BAT - adc_data.current.I_LL);


    Loop_Control();
    // loop_text(); // 保留独立测试函数，正式充电不调用
}

void PI_Controller_Init(PI_Controller *PI_Type,float K_p,float K_i) {
    PI_Type->K_p = K_p;
    PI_Type->K_i = K_i;

    PI_Type->error[0]=0;
    PI_Type->error[1]=0;

    PI_Type->output=0;
    PI_Type->output_max = 20.0f;
    PI_Type->output_min = -20.0f;
}

void PI_Controller_Calculate(PI_Controller *PI_Type,float target,float actual) {
    PI_Type->target =target;
    PI_Type->actual =actual;

    PI_Type->error[0] =  PI_Type->target-PI_Type->actual;
    PI_Type->output += PI_Type->K_p*(PI_Type->error[0]-PI_Type->error[1]) + PI_Type->K_i*PI_Type->error[0];

    if (PI_Type->output>PI_Type->output_max) {PI_Type->output=PI_Type->output_max;}
    if (PI_Type->output<PI_Type->output_min) {PI_Type->output=PI_Type->output_min;}

    PI_Type->error[1]=PI_Type->error[0];
}

void Loop_Control() {
    static float battery_power_sum = 0.0f;      //电池功率累加
    static uint16_t power_tick = 0U;            //功率环分频系数
    static uint8_t power_initialized = false;   //功率环初始化
    static uint8_t feedback_initialized = false;
    float vin = adc_data.volt.V_CHASS;          //输入电压
    float vcap = adc_data.volt.V_CAP;           //输出电压
    float voltage_target = Clamp(loop_var.V_target, 0.0f, CAP_VOLTAGE_MAX_V);               //目标输出电压限幅30V
    float current_limit = Clamp(loop_var.I_BAT_target, 0.0f, CHARGE_PEAK_CURRENT_MAX_A);    //输出电流限幅15A
    float power_target = loop_var.P_BAT_target;//电池目标功率

    /***************保护函数******************/
    if (vcap >= CAP_VOLTAGE_MAX_V) { loop_var.loop_protect.protect_flag = true; }

    if (loop_var.loop_protect.protect_flag ||
        !(vin > 1.0f && vcap >= 0.0f && voltage_target > 0.0f &&
          current_limit > 0.0f && power_target > 0.0f) ||
        !isfinite(vin) || !isfinite(power_target) || !isfinite(adc_data.current.I_BAT) ||
        !isfinite(adc_data.current.I_LL)) {
        PowerStage_Stop();
        loop_var.I_target = loop_var.I_applied = loop_var.I_BAT_ref = 0.0f;
        PI_Power_Controller.output = PI_Current_Controller.output = PI_Volt_Controller.output = 0.0f;
        PI_Power_Controller.error[1] = PI_Current_Controller.error[1] = PI_Volt_Controller.error[1] = 0.0f;
        battery_power_sum = 0.0f;
        power_tick = 0U;
        power_initialized = false;
        feedback_initialized = false;
        loop_var.charge_complete = false;
        loop_var.power_request = POWER_REQUEST_IDLE;
        return;
    }
    /***************************************/

    if (!feedback_initialized) {
        loop_var.I_BAT_feedback = adc_data.current.I_BAT;
        loop_var.I_LL_feedback = adc_data.current.I_LL;
        feedback_initialized = true;
    } else {
        loop_var.I_BAT_feedback += CURRENT_FEEDBACK_ALPHA * (adc_data.current.I_BAT - loop_var.I_BAT_feedback);
        loop_var.I_LL_feedback += CURRENT_FEEDBACK_ALPHA * (adc_data.current.I_LL - loop_var.I_LL_feedback);
    }
    loop_var.Duty = vcap / vin;//输出比上输入
    float chassis_power = vin * (loop_var.I_BAT_feedback - loop_var.I_LL_feedback);//底盘功率
    float battery_budget = fminf(power_target, vin * current_limit);//目标功率限制，取小值
    PowerStage_UpdatePowerRequest(chassis_power, battery_budget);

    /***************************************/
    // 电容低于10V且底盘仍需补能时锁住PWM，只由底盘低于80%需求功率解除
    if (vcap <= CAP_DISCHARGE_MIN_V &&
        (loop_var.power_request == POWER_REQUEST_DISCHARGE ||
         (loop_var.energy_direction == ENERGY_DISCHARGE && loop_var.pwm_enabled))) {
        loop_var.discharge_cutoff = true;//低电压预警
    }

    if (loop_var.discharge_cutoff) {
        PowerStage_Stop();
        loop_var.I_target = loop_var.I_applied = 0.0f;
        PI_Current_Controller.output = PI_Volt_Controller.output = 0.0f;
        PI_Current_Controller.error[1] = PI_Volt_Controller.error[1] = 0.0f;
        battery_power_sum = 0.0f;
        power_tick = 0U;
        power_initialized = false;
        if (chassis_power >= CHARGE_RESTART_POWER_RATIO * battery_budget) { return; }
        loop_var.discharge_cutoff = false;
    }

    // 满电后保持停充，避免目标附近0.1V小回差导致反复发几个脉冲。
    if (vcap <= voltage_target - CAP_CHARGE_RESTART_DROP_V) {
        loop_var.charge_complete = false;
    } else if (vcap >= voltage_target + CAP_CHARGE_STOP_MARGIN_V ||
               (vcap >= voltage_target - CAP_CHARGE_STOP_MARGIN_V &&
                loop_var.energy_direction == ENERGY_CHARGE && control_mode == CONTROL_MODE_CV &&
                PI_Volt_Controller.output <= 0.05f)) {
        loop_var.charge_complete = true;
    }
    if (loop_var.power_request == POWER_REQUEST_IDLE ||
        (loop_var.charge_complete && loop_var.power_request == POWER_REQUEST_CHARGE)) {
        if (loop_var.pwm_enabled) { PowerStage_Stop(); }
        loop_var.I_target = loop_var.I_applied = 0.0f;
        // 满电或功率平衡区电池只供底盘，不积累“必须补足60W”的误差。
        loop_var.I_BAT_ref = Clamp(chassis_power / vin, 0.0f, current_limit);
        PI_Power_Controller.output = loop_var.I_BAT_ref;
        PI_Current_Controller.output = 0.0f;
        // 停充时电压环让位，恢复后先由电流环建立电流，避免从零慢慢积分。
        PI_Volt_Controller.output = CHARGE_PEAK_CURRENT_MAX_A;
        PI_Current_Controller.error[1] = PI_Volt_Controller.error[1] = 0.0f;
        control_mode = loop_var.charge_complete ? CONTROL_MODE_CV : CONTROL_MODE_CC;
        battery_power_sum = 0.0f;
        power_tick = 0U;
        power_initialized = false;
        return;
    }
    /*****************功率环*******************/

    // 功率外环输出电池目标电流
    PI_Power_Controller.output_min = 0.0f;
    PI_Power_Controller.output_max = current_limit;
    if (!power_initialized) {
        PI_Power_Controller.output = battery_budget / vin;//初始化软启动
        PI_Power_Controller.error[1] = battery_budget - vin * loop_var.I_BAT_feedback;
        power_initialized = true;
    }
    PI_Power_Controller.output = Clamp(PI_Power_Controller.output, 0.0f, current_limit);
    battery_power_sum += vin * loop_var.I_BAT_feedback;

    if (++power_tick >= POWER_LOOP_DIVIDER) {
        float previous_ref = PI_Power_Controller.output;
        PI_Controller_Calculate(&PI_Power_Controller, battery_budget, battery_power_sum / POWER_LOOP_DIVIDER);

        //电容即将充满停止更新功率环输出
        if (previous_ref > 0.0f && control_mode == CONTROL_MODE_CV &&
            vcap >= voltage_target - 0.2f && PI_Power_Controller.output > previous_ref) {
            PI_Power_Controller.output = previous_ref;
        }
        battery_power_sum = 0.0f;
        power_tick = 0U;
    }
    loop_var.I_BAT_ref = PI_Power_Controller.output;

    /***************************************/

    // 充电仅允许正参考；放电允许有限正偏置，校正谷值参考与平均电流的零点差。
    float current_min = loop_var.power_request == POWER_REQUEST_DISCHARGE && vcap > CAP_DISCHARGE_MIN_V ?
                        -DISCHARGE_VALLEY_MAX_A : 0.0f;
    float current_max = loop_var.power_request == POWER_REQUEST_CHARGE ?
                        CHARGE_PEAK_CURRENT_MAX_A : DISCHARGE_ZERO_TRIM_MAX_A;
    PI_Current_Controller.output_min = current_min;
    PI_Current_Controller.output_max = current_max;
    PI_Current_Controller.output = Clamp(PI_Current_Controller.output, current_min, current_max);
    PI_Volt_Controller.output_min = 0.0f;
    PI_Volt_Controller.output_max = CHARGE_PEAK_CURRENT_MAX_A;

    PI_Controller_Calculate(&PI_Current_Controller, loop_var.I_BAT_ref, loop_var.I_BAT_feedback);
    PI_Controller_Calculate(&PI_Volt_Controller, voltage_target, vcap);

    // 原固定25V判断只改了模式标签，实际仍取小，容易让未建立的电压环压住充电。
    // 改成相对目标的恒压区；区外电压环让位，恢复补充时也先由电流环建立电流。
    if (vcap < voltage_target - CAP_CV_WINDOW_V) {
        PI_Volt_Controller.output = CHARGE_PEAK_CURRENT_MAX_A;
    }
    // 放电时仅电流环调谷值；正的零点修正不能被充电电压环截断。
    if (loop_var.power_request == POWER_REQUEST_DISCHARGE) {
        control_mode = CONTROL_MODE_CC;
        loop_var.I_target = PI_Current_Controller.output;
    } else {
        control_mode = PI_Volt_Controller.output < PI_Current_Controller.output ? CONTROL_MODE_CV : CONTROL_MODE_CC;
        loop_var.I_target = fminf(PI_Volt_Controller.output, PI_Current_Controller.output);
    }

    PI_Current_Controller.output = loop_var.I_target;

    //选择运行电流环的时候，跟踪电压环的输出
    if (control_mode == CONTROL_MODE_CC) {
        PI_Volt_Controller.output = Clamp(fmaxf(loop_var.I_target, 0.0f) +
                                         PI_Volt_Controller.K_p * (voltage_target - vcap),
                                         0.0f, CHARGE_PEAK_CURRENT_MAX_A);
    }
    //根据电流请求选择充电或放电，更新 DAC 参数，并决定PWM是否启动
    PowerStage_Apply(loop_var.I_target, voltage_target);
}

void loop_text() {
    static float daccode = 0.0f;
    loop_var.Duty = adc_data.volt.V_CAP/adc_data.volt.V_CHASS;
    //buck_text

    // HAL_COMP_Start(&hcomp1);
    // HRTIM1->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_B].CMP1xR = 27200*0.1f;//boost保持上管最大90%占空比，给自举电容充电
    // PI_Controller_Calculate(&PI_Volt_Controller, 15.0f, adc_data.volt.V_CAP);
    // loop_var.I_target = PI_Volt_Controller.output;
    // daccode = IL_TO_DAC(loop_var.I_target);
    // if (daccode > 4095.0f) { daccode = 4095.0f; }
    // if (daccode < 0.0f) { daccode = 0.0f; }
    // HAL_DACEx_SawtoothWaveGenerate(&hdac1, DAC_CHANNEL_1, DAC_SAWTOOTH_POLARITY_DECREMENT,
    //                              (uint32_t)daccode, 180);

    //boost_text

    HAL_COMP_Start(&hcomp2);
    HRTIM1->sTimerxRegs[HRTIM_TIMERINDEX_TIMER_A].CMP1xR = 27200*0.9f;
    PI_Controller_Calculate(&PI_Volt_Controller, 28.0f, adc_data.volt.V_CAP);
    loop_var.I_target = PI_Volt_Controller.output;
    daccode = IL_TO_DAC(loop_var.I_target);
    if (daccode > 4095.0f) { daccode = 4095.0f; }
    if (daccode < 0.0f) { daccode = 0.0f; }
    HAL_DACEx_SawtoothWaveGenerate(&hdac1, DAC_CHANNEL_2, DAC_SAWTOOTH_POLARITY_DECREMENT,
                                 (uint32_t)daccode, 180);

    //恒功率


}

