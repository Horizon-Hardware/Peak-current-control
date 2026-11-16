//
// Created by CRB on 2026/11/9.
//

#ifndef G474CBT6_2026_7_2_LOOP_H
#define G474CBT6_2026_7_2_LOOP_H


#include <stdint.h>

typedef float float32_t;

typedef struct {
    // ---- 系数参数 (Coefficients) ----
    // 零点系数
    float32_t b0;
    float32_t b1;
    float32_t b2;
    float32_t b3;

    // 极点系数
    float32_t a1;
    float32_t a2;
    float32_t a3;


    //误差历史 e[n-1], e[n-2], e[n-3]
    float32_t e_1;
    float32_t e_2;
    float32_t e_3;

    //输出历史 u[n-1], u[n-2], u[n-3]
    float32_t u_1;
    float32_t u_2;
    float32_t u_3;

    float32_t out_max;
    float32_t out_min;

} Compensator3P3Z_t;

void Compensator3P3Z_Init(Compensator3P3Z_t *comp);
void Compensator3P3Z_SetCoeffs(Compensator3P3Z_t *comp, float32_t *coeffs);
void Compensator3P3Z_SetLimits(Compensator3P3Z_t *comp, float32_t min, float32_t max);
float32_t Compensator3P3Z_Update(Compensator3P3Z_t *comp, float32_t ref, float32_t meas);
void Compensator3P3Z_Reset(Compensator3P3Z_t *comp);

#endif //G474CBT6_2026_7_2_LOOP_H