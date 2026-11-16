#include "actual_control.c"
#include "gains.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

HRTIM_TypeDef test_hrtim;
HRTIM_HandleTypeDef hhrtim1;
DAC_HandleTypeDef hdac1;
static DAC_TypeDef dac_regs;
static unsigned starts, updates, violations, checks;
#define CHECK(c, m) do { ++checks; if (!(c)) { \
    fprintf(stderr, "FAIL line %d: %s\n", __LINE__, m); exit(1); } } while (0)

HAL_StatusTypeDef HAL_HRTIM_WaveformOutputStart(HRTIM_HandleTypeDef *h, uint32_t o) {
    (void)h; (void)o; ++starts;
    if (loop_var.energy_direction == ENERGY_DISCHARGE) {
        if (loop_var.discharge_cutoff || adc_data.volt.V_CAP <= CAP_DISCHARGE_MIN_V ||
            !(dac_regs.STR1 & DAC_STR1_STDIR1) || !(dac_regs.STR2 & DAC_STR2_STDIR2) ||
            !(test_hrtim.sCommonRegs.EECR1 & HRTIM_EECR1_EE1POL) ||
            test_hrtim.sTimerxRegs[0].SETx1R != (HRTIM_OUTPUTSET_TIMCMP1 | HRTIM_OUTPUTSET_EEV_4) ||
            test_hrtim.sTimerxRegs[1].RSTx1R != (HRTIM_OUTPUTRESET_TIMCMP1 | HRTIM_OUTPUTRESET_EEV_1)) ++violations;
    } else if ((dac_regs.STR1 & DAC_STR1_STDIR1) || (dac_regs.STR2 & DAC_STR2_STDIR2) ||
               (test_hrtim.sCommonRegs.EECR1 & (HRTIM_EECR1_EE1POL | HRTIM_EECR1_EE4POL))) ++violations;
    return HAL_OK;
}
HAL_StatusTypeDef HAL_HRTIM_WaveformOutputStop(HRTIM_HandleTypeDef *h, uint32_t o) {
    (void)h; (void)o; return HAL_OK;
}
HAL_StatusTypeDef HAL_HRTIM_SoftwareUpdate(HRTIM_HandleTypeDef *h, uint32_t t) {
    (void)h; (void)t; ++updates;
    if (loop_var.pwm_enabled) ++violations;
    return HAL_OK;
}

static void sample(float cap, float chassis_w, float converter_i) {
    adc_data.volt.V_CHASS = 24; adc_data.volt.V_CAP = cap;
    adc_data.current.I_LL = converter_i;
    adc_data.current.I_BAT = chassis_w / 24 + converter_i;
    Loop_Control();
}
static void configure(void) {
    memset(&test_hrtim, 0, sizeof(test_hrtim)); memset(&dac_regs, 0, sizeof(dac_regs));
    hdac1.Instance = &dac_regs; hhrtim1.Instance = &test_hrtim;
    test_hrtim.sTimerxRegs[0].CMP4xR = test_hrtim.sTimerxRegs[1].CMP4xR = 1088;
    test_hrtim.sTimerxRegs[0].CMP3xR = test_hrtim.sTimerxRegs[1].CMP3xR = 2720;
    loop_var = (Loop_variable){.V_target = 28, .I_BAT_target = 5, .P_BAT_target = 60,
                              .energy_direction = ENERGY_CHARGE};
    control_mode = CONTROL_MODE_CV;
    PI_Controller_Init(&PI_Volt_Controller, VOLT_KP, VOLT_KI);
    PI_Controller_Init(&PI_Current_Controller, CURRENT_KP, CURRENT_KI);
    PI_Controller_Init(&PI_Power_Controller, POWER_KP, POWER_KI);
    loop_var.loop_protect.protect_flag = true; sample(20, 60, 0);
    loop_var.loop_protect.protect_flag = false;
    loop_var.Duty = 20.f / 24;
    PowerStage_SetDirection(ENERGY_CHARGE);
}
static void apply_at(float ratio, EnergyDirection direction) {
    loop_var.power_request = direction == ENERGY_CHARGE ? POWER_REQUEST_CHARGE : POWER_REQUEST_DISCHARGE;
    loop_var.Duty = direction == ENERGY_CHARGE ? ratio : 1.f / ratio;
    PowerStage_Apply(direction * 3.f, 28);
}
static void settle(float ratio, EnergyDirection direction) {
    for (unsigned k = 0; k < 240; ++k) apply_at(ratio, direction);
}

static void mode_checks(void) {
    const float boundaries[] = {BUCK_BOOST_RATIO_LOW,
        .5f * (BUCK_BOOST_RATIO_LOW + BUCK_BOOST_RATIO_HIGH), BUCK_BOOST_RATIO_HIGH};
    const PowerStageMode lower[] = {CONTROL_MODE_BUCK, CONTROL_MODE_BUCKBOOST, CONTROL_MODE_BOOSTBUCK};
    const PowerStageMode upper[] = {CONTROL_MODE_BUCKBOOST, CONTROL_MODE_BOOSTBUCK, CONTROL_MODE_BOOST};
    for (int d = -1; d <= 1; d += 2) {
        for (unsigned b = 0; b < 3; ++b) {
            float edge = boundaries[b];
            configure(); apply_at(edge - .05f, (EnergyDirection)d); settle(edge - .05f, (EnergyDirection)d);
            settle(edge + MODE_RATIO_HYSTERESIS - .003f, (EnergyDirection)d);
            CHECK(power_stage_mode == lower[b], "rising voltage must wait for upper hysteresis threshold");
            settle(edge + MODE_RATIO_HYSTERESIS + .003f, (EnergyDirection)d);
            CHECK(power_stage_mode == upper[b], "rising voltage must enter next mode past upper threshold");
            settle(edge - MODE_RATIO_HYSTERESIS + .003f, (EnergyDirection)d);
            CHECK(power_stage_mode == upper[b], "falling voltage must retain mode within hysteresis");
            settle(edge - MODE_RATIO_HYSTERESIS - .003f, (EnergyDirection)d);
            CHECK(power_stage_mode == lower[b], "falling voltage must exit past lower threshold");

            configure(); apply_at(edge, (EnergyDirection)d); settle(edge, (EnergyDirection)d);
            float held = loop_var.mode_blend;
            uint32_t ac = dac_regs.STR1, bc = dac_regs.STR2, ca = CMP_A, cb = CMP_B;
            for (unsigned k = 0; k < 2000; ++k) {
                // 5ms plateaus exceed the existing filter time constant: test real memory/hysteresis.
                apply_at(edge + ((k / 100) % 2 ? .01f : -.01f), (EnergyDirection)d);
                CHECK(fabsf(loop_var.mode_blend - held) < .00001f, "ratio jitter must not change actual blend");
                CHECK(dac_regs.STR1 == ac && dac_regs.STR2 == bc && CMP_A == ca && CMP_B == cb,
                      "both actual DAC thresholds and PWM windows must hold, including reverse dynamic window");
            }
        }
        configure(); apply_at(.75f, (EnergyDirection)d); settle(.75f, (EnergyDirection)d);
        unsigned seen = 0;
        for (unsigned pass = 0; pass < 2; ++pass) {
            for (unsigned k = 0; k <= 800; ++k) {
                float ratio = pass ? 1.15f - .0005f * k : .75f + .0005f * k;
                float previous = loop_var.mode_blend;
                apply_at(ratio, (EnergyDirection)d);
                CHECK(fabsf(loop_var.mode_blend - previous) <= MODE_BLEND_MAX_STEP + .00001f,
                      "all mode changes must respect continuous blend slew limit");
                CHECK(loop_var.pwm_enabled, "mode hysteresis alone must never stop PWM");
                CHECK(CMP_A <= BUCK_CMP_MAX && CMP_B <= BUCK_CMP_MAX, "PWM windows remain bounded");
                seen |= 1U << (power_stage_mode - CONTROL_MODE_BUCK);
            }
        }
        CHECK(seen == 15, "both directions must traverse all four modes");
        CHECK(loop_var.mode_blend == 0, "slow return must reach exact Buck endpoint");
    }
    puts("PASS: all three mode boundaries in both directions, slow ratio noise, actual DAC/CMP holding, bidirectional sweeps.");
}

static void power_checks(void) {
    configure();
    const float demand[] = {60, 58, 59.4f, 59.5f, 59, 62, 60.6f, 60.5f, 61, 58};
    const PowerRequest expected[] = {POWER_REQUEST_IDLE, POWER_REQUEST_CHARGE, POWER_REQUEST_CHARGE,
        POWER_REQUEST_IDLE, POWER_REQUEST_IDLE, POWER_REQUEST_DISCHARGE, POWER_REQUEST_DISCHARGE,
        POWER_REQUEST_IDLE, POWER_REQUEST_IDLE, POWER_REQUEST_CHARGE};
    for (unsigned k = 0; k < sizeof(demand) / sizeof(demand[0]); ++k) {
        PowerStage_UpdatePowerRequest(demand[k], 60);
        CHECK(loop_var.power_request == expected[k], "power Schmitt trigger must use distinct start/stop thresholds");
    }
    loop_var.power_request = POWER_REQUEST_IDLE;
    PowerStage_UpdatePowerRequest(9.5f, 10);
    CHECK(loop_var.power_request == POWER_REQUEST_CHARGE, "small budgets must scale the absolute dead band");
    PowerStage_UpdatePowerRequest(9.9f, 10);
    CHECK(loop_var.power_request == POWER_REQUEST_IDLE, "small-budget exit threshold scales with entry");

    configure(); sample(24, 60, 0);
    unsigned before_starts = starts, before_updates = updates;
    for (unsigned k = 0; k < 10000; ++k) {
        sample(24, 60 + ((k / 20) % 2 ? 1.2f : -1.2f), 0);
        CHECK(loop_var.power_request == POWER_REQUEST_IDLE && !loop_var.pwm_enabled && loop_var.I_target == 0,
              "equal-budget noise must not start PWM or switch energy direction");
    }
    CHECK(starts == before_starts && updates == before_updates, "balance-point noise must not reconfigure DAC polarity");
    CHECK(PI_Current_Controller.output == 0 && PI_Power_Controller.output <= 5,
          "idle must clear current PI and prevent power PI windup");
    unsigned ticks = 0;
    do { sample(24, 90, 0); ++ticks; }
    while (!loop_var.pwm_enabled && ticks < 20);
    CHECK(loop_var.power_request == POWER_REQUEST_DISCHARGE && loop_var.energy_direction == ENERGY_DISCHARGE &&
          loop_var.pwm_enabled && ticks < 10, "large support demand must bypass idle without long confirmation delays");
    printf("PASS: 60W balance noise, power hysteresis, small budgets, PI idle tracking; 90W support PWM in %u mocked control ticks.\n", ticks);
}

static float cap = 18, chassis = 10, port_i;
static void run_plant(float seconds) {
    for (unsigned k = 0; k < (unsigned)(seconds * 20000); ++k) {
        sample(cap, chassis, port_i);
        float requested_i = loop_var.pwm_enabled ? .65f * loop_var.I_applied : 0;
        port_i += .1f * (requested_i - port_i);
        cap = sqrtf(fmaxf(0, cap * cap + 2 * .00005f / 5 * (24 * port_i)));
    }
}
static void functional_checks(void) {
    configure(); cap = 18; chassis = 10; port_i = 0;
    run_plant(5);
    CHECK(port_i > 0 && fabsf(chassis + 24 * port_i - 60) < 1, "charging must still use battery budget");
    chassis = 90; run_plant(5);
    CHECK(port_i < 0 && fabsf(chassis + 24 * port_i - 60) < 1, "reverse support must still regulate battery power");
    chassis = 10; run_plant(5);
    CHECK(port_i > 0 && fabsf(chassis + 24 * port_i - 60) < 1, "charge must resume after support");
    loop_var.P_BAT_target = 30; run_plant(4);
    CHECK(fabsf(chassis + 24 * port_i - 30) < 1, "power state must follow changed target");
    loop_var.P_BAT_target = 60; loop_var.I_BAT_target = 1; chassis = 40; run_plant(5);
    CHECK(fabsf(chassis + 24 * port_i - 24) < 1, "hysteresis must use current-limited budget");
    cap = 9.99f; run_plant(.01f);
    CHECK(loop_var.discharge_cutoff && !loop_var.pwm_enabled, "10V cutoff must latch outputs off");
    chassis = 19.2f; cap = 12; run_plant(.02f);
    CHECK(loop_var.discharge_cutoff && !loop_var.pwm_enabled, "exactly 80 percent must retain cutoff");
    chassis = 18; cap = 9.8f; run_plant(2);
    CHECK(!loop_var.discharge_cutoff && port_i > 0, "below 80 percent allows charging a capacitor below 10V");

    configure(); sample(28.06f, 10, 0);
    CHECK(loop_var.charge_complete && !loop_var.pwm_enabled, "full capacitor must hold off");
    unsigned count = starts;
    for (unsigned k = 0; k < 1000; ++k) sample(28 + (k % 2 ? .08f : -.08f), 10, 0);
    CHECK(starts == count && !loop_var.pwm_enabled, "full-charge voltage noise must not produce bursts");
    for (unsigned k = 0; k < 12; ++k) sample(28, 90, 0);
    CHECK(loop_var.pwm_enabled && loop_var.energy_direction == ENERGY_DISCHARGE, "full hold must permit discharge support");
    for (unsigned k = 0; k < 12; ++k) sample(27.5f, 10, 0);
    CHECK(!loop_var.charge_complete && loop_var.power_request == POWER_REQUEST_CHARGE, "27.5V permits recharge");
    sample(30.1f, 10, 0);
    CHECK(loop_var.loop_protect.protect_flag && !loop_var.pwm_enabled, "30V protection remains effective");
    puts("PASS: closed-loop average plant charge/support, setpoint/current limit, 10V/80% recovery, full hold/recharge, overvoltage.");
}

static void current_checks(void) {
    configure(); unsigned before = updates;
    for (unsigned k = 0; k < 100; ++k) PowerStage_Apply(k % 2 ? .02f : -.02f, 28);
    CHECK(updates == before && !loop_var.pwm_enabled, "tiny signed requests cannot chatter hardware direction");
    loop_var.power_request = POWER_REQUEST_CHARGE;
    PowerStage_Apply(3, 28); PowerStage_Apply(6, 28);
    CHECK(loop_var.pwm_enabled && loop_var.I_applied == 6, "current step response must remain immediate");
    loop_var.power_request = POWER_REQUEST_DISCHARGE;
    PowerStage_Apply(-3, 28);
    CHECK(!loop_var.pwm_enabled && loop_var.I_applied == 0 && loop_var.energy_direction == ENERGY_DISCHARGE,
          "reversal must insert one off interval");
    PowerStage_Apply(-3, 28);
    CHECK(loop_var.pwm_enabled && loop_var.I_applied == -3, "reverse starts next call");
    before = updates; unsigned count = starts;
    const float trim[] = {-.04f, -.005f, 0, .02f, .5f};
    uint32_t previous_code = 0;
    for (unsigned k = 0; k < sizeof(trim) / sizeof(trim[0]); ++k) {
        PowerStage_Apply(trim[k], 28);
        CHECK(loop_var.pwm_enabled && updates == before && starts == count && loop_var.energy_direction == ENERGY_DISCHARGE,
              "small reverse/zero/positive trim must not burst PWM or switch hardware polarity");
        uint32_t code = dac_regs.STR2 & 4095U;
        CHECK(code >= previous_code, "positive reverse trim must actually reach the active DAC threshold");
        previous_code = code;
    }
    loop_var.power_request = POWER_REQUEST_CHARGE;
    PowerStage_Apply(3, 28); CHECK(!loop_var.pwm_enabled, "return to charge also leaves one off interval");
    PowerStage_Apply(3, 28); CHECK(loop_var.pwm_enabled, "charge restarts next call");
    CHECK(CHARGE_PEAK_CURRENT_MAX_A == 15 && DISCHARGE_VALLEY_MAX_A == 15, "preserve 15A command limits");
    CHECK(!violations, "no PWM start with invalid direction/cutoff or register reconfiguration while on");
    loop_var.power_request = POWER_REQUEST_IDLE; PowerStage_Apply(-3, 28);
    CHECK(!loop_var.pwm_enabled && loop_var.I_applied == 0, "idle power request stops PWM regardless of old PI output");
    puts("PASS: demand-selected direction, continuous reverse zero trim, immediate command steps, one-call reversal interval, 15A commands.");
}

static void small_support_checks(void) {
    /* Include a configurable valley-reference offset to cover the case that
     * average current zero is not the same as DAC-reference zero. */
    for (unsigned bias = 0; bias < 2; ++bias) {
        configure();
        float converter_i = 0, offset = bias ? 1.0f : 0;
        unsigned count = 0, changes = 0;
        double power_sum = 0; unsigned n = 0;
        for (unsigned k = 0; k < 60000; ++k) {
            sample(27, 70, converter_i);
            float desired_i = loop_var.pwm_enabled ? .65f * (loop_var.I_applied - offset) : 0;
            converter_i += .1f * (desired_i - converter_i);
            if (k == 40000) { count = starts; changes = updates; }
            if (k >= 40000) {
                power_sum += 70 + 24 * converter_i; ++n;
                CHECK(loop_var.pwm_enabled && loop_var.energy_direction == ENERGY_DISCHARGE,
                      "27V/70W support must stay continuously enabled after settling");
            }
        }
        CHECK(fabs(power_sum / n - 60) < .25 && converter_i < 0, "70W chassis must obtain about 10W from capacitor");
        CHECK(starts == count && updates == changes, "small support must not burst or reconfigure direction");
        CHECK(!bias || loop_var.I_target > 0, "offset case must remain reverse while PI uses positive zero trim");
        printf("PASS: 27V/70W average model, offset=%.1fA, Pbat=%.3fW I_target=%.3fA, no steady PWM restarts.\n",
               offset, power_sum / n, loop_var.I_target);
    }
}

static void feedback_checks(void) {
    configure(); sample(27, 70, 0);
    adc_data.current.I_BAT += 1;
    float raw = adc_data.current.I_BAT;
    Loop_Control();
    CHECK(adc_data.current.I_BAT == raw, "filtering must preserve raw ADC debug readings");
    CHECK(fabsf(PI_Current_Controller.actual - loop_var.I_BAT_feedback) < .00001f,
          "current PI must use filtered battery feedback");
    CHECK(fabsf(raw - loop_var.I_BAT_feedback) > .5f, "single-sample current noise must be attenuated");
    configure(); sample(24, 60, 0);
    for (unsigned k = 0; k < 500; ++k) {
        sample(24, 60, k % 2 ? .2f : -.2f);
        CHECK(loop_var.power_request == POWER_REQUEST_IDLE && !loop_var.pwm_enabled,
              "common branch-current noise must not create false chassis power demand");
    }
    CHECK(fabsf(loop_var.I_BAT_feedback - 2.5f) < .04f,
          "alternating battery noise must be attenuated by matched current filters");
    puts("PASS: filtered PI input, untouched ADC readings, matched branch filters, balance-point noise.");
}
int main(void) {
    mode_checks(); power_checks(); functional_checks(); current_checks(); small_support_checks(); feedback_checks();
    printf("PASS: %u assertions; mock RAM/registers only, no MCU access.\n", checks);
    return 0;
}
