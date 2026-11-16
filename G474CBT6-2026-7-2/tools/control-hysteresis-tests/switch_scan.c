#define main average_checks_main
#include "check.c"
#undef main

/* Fixed-voltage numerical check, not a measurement. 10ns steps, 10uH/80mOhm,
 * 80ns deadtime and ideal body diodes. ADC feedback represents a smoothed input
 * current, not real ADC aperture/error. DAC step timing follows CMP2 spacing.
 * The model cannot prove hardware stability or reproduce every analog artifact. */
int main(int argc, char **argv) {
    configure();
    double vc = argc > 1 ? atof(argv[1]) : 24;
    double load = argc > 2 ? atof(argv[2]) : 70;
    double duration = argc > 3 ? atof(argv[3]) : .20;
    double noise = argc > 4 ? atof(argv[4]) : 0;
    double input = argc > 5 ? atof(argv[5]) : 24;
    double il = 0, port = 0, power = 0, power_sq = 0, csum = 0, csq = 0;
    double command_min = 100, command_max = -100, peak = -100, valley = 100;
    unsigned a = 0, b = 1, old_a = a, old_b = b, dead_a = 0, dead_b = 0;
    unsigned samples = 0, cycles = 0, enabled = 0, start_count = 0, update_count = 0;
    unsigned total = (unsigned)(duration * 1e8), measure = total / 2;
    for (unsigned k = 0; k < total; ++k) {
        unsigned phase = k % 500, ap = phase, bp = (phase + 250) % 500;
        if (k % 5000 == 0) {
            adc_data.volt.V_CHASS = input;
            adc_data.volt.V_CAP = vc + noise * ((k / 5000) % 2 ? 1 : -1);
            adc_data.current.I_LL = port;
            adc_data.current.I_BAT = port + load / input;
            Loop_Control();
            if (k >= measure) {
                double p = input * port + load;
                power += p; power_sq += p * p; ++samples;
                enabled += !!loop_var.pwm_enabled;
                if (loop_var.I_target < command_min) command_min = loop_var.I_target;
                if (loop_var.I_target > command_max) command_max = loop_var.I_target;
            } else { start_count = starts; update_count = updates; }
        }
        double acode = (dac_regs.STR1 & 4095U) + (dac_regs.STR1 & DAC_STR1_STDIR1 ? 1. : -1.) *
            (unsigned)(ap * 54.4 / 1000) * ((dac_regs.STR1 >> 16) & 65535U) / 16.;
        double bcode = (dac_regs.STR2 & 4095U) + (dac_regs.STR2 & DAC_STR2_STDIR2 ? 1. : -1.) *
            (unsigned)(bp * 54.4 / 1000) * ((dac_regs.STR2 >> 16) & 65535U) / 16.;
        acode = fmax(0, fmin(4095, acode)); bcode = fmax(0, fmin(4095, bcode));
        double signal = IL_TO_DAC(il);
        unsigned acmp = (unsigned)(CMP_A / 54.4), bcmp = (unsigned)(CMP_B / 54.4);
        unsigned astart = (unsigned)(test_hrtim.sTimerxRegs[0].CMP4xR / 54.4);
        unsigned bstart = (unsigned)(test_hrtim.sTimerxRegs[1].CMP4xR / 54.4);
        unsigned ablank = (unsigned)(test_hrtim.sTimerxRegs[0].CMP3xR / 54.4);
        unsigned bblank = (unsigned)(test_hrtim.sTimerxRegs[1].CMP3xR / 54.4);
        if (loop_var.energy_direction == ENERGY_CHARGE) {
            if (ap == astart) a = 1;
            if (bp == bstart) b = 0;
            if (ap >= acmp) a = 0;
            if (bp >= bcmp) b = 1;
            if (ap >= ablank && signal > acode) a = 0;
            if (bp >= bblank && signal > bcode) b = 1;
        } else {
            if (ap == astart) a = 0;
            if (bp == bstart) b = 1;
            if (ap >= acmp) a = 1;
            if (bp >= bcmp) b = 0;
            if (ap >= ablank && signal < acode) a = 1;
            if (bp >= bblank && signal < bcode) b = 0;
        }
        if (a != old_a) { dead_a = 8; old_a = a; }
        if (b != old_b) { dead_b = 8; old_b = b; }
        unsigned qa = dead_a ? il < 0 : a, qb = dead_b ? il > 0 : b;
        if (dead_a) --dead_a;
        if (dead_b) --dead_b;
        double incoming;
        if (loop_var.pwm_enabled) {
            il += 1e-8 / 1e-5 * (qa * input - qb * vc - .08 * il);
            incoming = qa * il;
        } else {
            double change = 1e-8 / 1e-5 * ((il > 0 ? -vc : input) - .08 * il);
            if ((il > 0 && il + change < 0) || (il < 0 && il + change > 0) || il == 0) il = 0;
            else il += change;
            incoming = il < 0 ? il : 0;
        }
        port += .0001 * (incoming - port);
        if (k >= measure) {
            if (il > peak) peak = il;
            if (il < valley) valley = il;
            if (phase == 499) { csum += il; csq += il * il; ++cycles; }
        }
        if (k % 5000 == 0) CHECK(isfinite(il) && fabs(il) < 35, "switching-model current outside operating range");
    }
    double p = power / samples;
    printf("Vcap=%.1f Vin=%.1f chassis=%.1f Pbat=%.3f Pstd=%.3f cycle_std=%.3f "
           "PWM=%.1f%% starts=%u direction_changes=%u Icmd=[%.3f,%.3f] IL=[%.3f,%.3f]\n",
           vc, input, load, p, sqrt(fmax(0, power_sq / samples - p * p)),
           sqrt(fmax(0, csq / cycles - pow(csum / cycles, 2))),
           100. * enabled / samples, starts - start_count, updates - update_count,
           command_min, command_max, valley, peak);
    CHECK(!loop_var.loop_protect.protect_flag, "switching scan hit unexpected protection");
    CHECK(fabs(p - 60) < 3, "switching model must deliver required average power");
    return 0;
}
