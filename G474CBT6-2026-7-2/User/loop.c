//
// Created by CRB on 2026/11/9.
//

#include "loop.h"

Compensator3P3Z_t comp_i;
Compensator3P3Z_t comp_v;
Compensator3P3Z_t comp_p;

void Compensator3P3Z_Init(Compensator3P3Z_t *comp) {
    Compensator3P3Z_Reset(comp);

    comp->out_max = 0.9f;
    comp->out_min = 0.0f;

    comp->b0 = 0.0f; comp->b1 = 0.0f; comp->b2 = 0.0f; comp->b3 = 0.0f;
    comp->a1 = 0.0f; comp->a2 = 0.0f; comp->a3 = 0.0f;
}

void Compensator3P3Z_SetCoeffs(Compensator3P3Z_t *comp, float32_t *coeffs) {
    comp->b0 = coeffs[0];
    comp->b1 = coeffs[1];
    comp->b2 = coeffs[2];
    comp->b3 = coeffs[3];

    comp->a1 = coeffs[4];
    comp->a2 = coeffs[5];
    comp->a3 = coeffs[6];
}

void Compensator3P3Z_SetLimits(Compensator3P3Z_t *comp, float32_t min, float32_t max) {
    comp->out_min = min;
    comp->out_max = max;
}


void Compensator3P3Z_Reset(Compensator3P3Z_t *comp) {
    comp->e_1 = 0.0f;
    comp->e_2 = 0.0f;
    comp->e_3 = 0.0f;

    comp->u_1 = 0.0f;
    comp->u_2 = 0.0f;
    comp->u_3 = 0.0f;
}


float32_t Compensator3P3Z_Update(Compensator3P3Z_t *comp, float32_t ref, float32_t meas) {
    float32_t e0; //当前误差 e[n]
    float32_t u0; //当前输出 u[n]

    e0 = ref - meas;

    // u[n] = b0*e[n] + b1*e[n-1] + b2*e[n-2] + b3*e[n-3] + a1*u[n-1] + a2*u[n-2] + a3*u[n-3]
    u0 = (comp->b0 * e0)
       + (comp->b1 * comp->e_1)
       + (comp->b2 * comp->e_2)
       + (comp->b3 * comp->e_3)
       + (comp->a1 * comp->u_1)
       + (comp->a2 * comp->u_2)
       + (comp->a3 * comp->u_3);


    comp->e_3 = comp->e_2;
    comp->e_2 = comp->e_1;
    comp->e_1 = e0;

    comp->u_3 = comp->u_2;
    comp->u_2 = comp->u_1;
    comp->u_1 = u0;

    return u0;
}